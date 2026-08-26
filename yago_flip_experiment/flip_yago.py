#!/usr/bin/env python3
"""
Build YAGO4.5-10-flip: a variant in which chosen functional properties are
replaced by their inverse, turning them into inverse-functional properties.

For each flipped property P:
    data : every (s, P, o)  ->  (o, P-inverse, s)
    TBox : P's axioms are removed; P-inverse is declared
           rdf:Property + owl:InverseFunctionalProperty
           with domain and range SWAPPED.

No owl:inverseOf is emitted. P no longer exists in the data, and asserting the
inverse link would recreate exactly the twin relation this dataset exists to
avoid -- a rule could then invert one into the other for free (the leakage seen
in NELL, where haswife/wifeof and acquired/acquiredby both occur).

The control for this dataset is the ORIGINAL YAGO4.5-10: same facts, same
entities, only the direction reversed.
"""
import argparse, os, sys

SCHEMA = "http://schema.org/"
YAGO = "http://yago-knowledge.org/resource/"
RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
RDF_PROPERTY = "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property"
RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain"
RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range"
OWL_FUNC = "http://www.w3.org/2002/07/owl#FunctionalProperty"
OWL_IFUNC = "http://www.w3.org/2002/07/owl#InverseFunctionalProperty"

DEFAULT_FLIPS = ["birthPlace", "deathPlace"]


def inverse_uri(short):
    """schema.org/birthPlace -> yago-knowledge.org/resource/birthPlaceOf

    The inverse lives in the YAGO namespace, not schema.org: schema.org does not
    define these terms, and minting new URIs under someone else's namespace
    would be wrong.
    """
    return YAGO + short + "Of"


def rewrite_tsv(src, dst, flip_map, counts):
    with open(src) as fin, open(dst, "w") as fout:
        for line in fin:
            p = line.rstrip("\n").split("\t")
            if len(p) < 3:
                fout.write(line)
                continue
            s, r, o = p[0], p[1], p[2]
            inv = flip_map.get(r)
            if inv:
                fout.write(f"{o}\t{inv}\t{s}\n")
                counts[r] += 1
            else:
                fout.write(line)


def rewrite_nt(src, dst, flip_map, counts):
    """N-Triples: <s> <p> <o> . -- flip only the triples whose predicate is flipped."""
    with open(src) as fin, open(dst, "w") as fout:
        for line in fin:
            t = line.rstrip("\n").rstrip()
            if not t.endswith("."):
                fout.write(line)
                continue
            body = t[:-1].strip()
            if not body.startswith("<"):
                fout.write(line)
                continue
            # split into exactly three terms; object may be a literal, in which
            # case we never flip it (flipped predicates are all object properties)
            try:
                s_end = body.index(">") + 1
                subj = body[:s_end]
                rest = body[s_end:].lstrip()
                p_end = rest.index(">") + 1
                pred = rest[:p_end]
                obj = rest[p_end:].strip()
            except ValueError:
                fout.write(line)
                continue
            r = pred[1:-1]
            inv = flip_map.get(r)
            if inv and obj.startswith("<") and obj.endswith(">"):
                fout.write(f"{obj} <{inv}> {subj} .\n")
                counts[r] += 1
            else:
                fout.write(line)


def rewrite_tbox(src, dst, flips, report):
    """Drop each flipped property's axioms; emit the inverse with domain/range swapped."""
    drop_subjects = {SCHEMA + s for s in flips}
    captured = {u: {"domain": None, "range": None} for u in drop_subjects}
    dropped = 0
    with open(src) as fin, open(dst, "w") as fout:
        for line in fin:
            t = line.rstrip("\n").rstrip()
            if t.endswith(".") and t.startswith("<"):
                try:
                    s_end = t.index(">") + 1
                    subj = t[1:s_end - 1]
                except ValueError:
                    fout.write(line)
                    continue
                if subj in drop_subjects:
                    rest = t[s_end:-1].strip()
                    p_end = rest.index(">") + 1
                    pred = rest[1:p_end - 1]
                    obj = rest[p_end:].strip()
                    if pred == RDFS_DOMAIN:
                        captured[subj]["domain"] = obj
                    elif pred == RDFS_RANGE:
                        captured[subj]["range"] = obj
                    dropped += 1
                    continue          # drop the original axiom
            fout.write(line)

        # append the inverse declarations
        fout.write("\n")
        for short in flips:
            orig = SCHEMA + short
            inv = inverse_uri(short)
            dom, rng = captured[orig]["domain"], captured[orig]["range"]
            fout.write(f"<{inv}> <{RDF_TYPE}> <{RDF_PROPERTY}> .\n")
            fout.write(f"<{inv}> <{RDF_TYPE}> <{OWL_IFUNC}> .\n")
            # SWAPPED: the inverse's domain is the original's range, and vice versa.
            # Without this the semantic engine rejects every flipped triple.
            if rng:
                fout.write(f"<{inv}> <{RDFS_DOMAIN}> {rng} .\n")
            if dom:
                fout.write(f"<{inv}> <{RDFS_RANGE}> {dom} .\n")
            report.append(f"  {orig}")
            report.append(f"    -> {inv}  [owl:InverseFunctionalProperty]")
            report.append(f"       domain {rng}   (was range)")
            report.append(f"       range  {dom}   (was domain)")
    return dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="../data/YAGO4.5/data")
    ap.add_argument("--out", default="data")
    ap.add_argument("--flip", nargs="+", default=DEFAULT_FLIPS,
                    help=f"local names of properties to flip (default: {DEFAULT_FLIPS})")
    ap.add_argument("--skip-full-graph", action="store_true",
                    help="skip the 614MB full_graph.nt (only needed by mat-eval)")
    ap.add_argument("--only-full-graph", action="store_true",
                    help="rewrite ONLY full_graph.nt, leaving TBox and splits alone")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    flip_map = {SCHEMA + s: inverse_uri(s) for s in args.flip}
    counts = {k: 0 for k in flip_map}
    report = ["flipped properties:"]

    P = "YAGO4.5-10"
    Q = "YAGO4.5-10-flip"

    if args.only_full_graph:
        print("rewriting full_graph.nt only ...")
        rewrite_nt(f"{args.src}/{P}_full_graph.nt", f"{args.out}/{Q}_full_graph.nt",
                   flip_map, counts)
        for r, n in counts.items():
            print(f"  {r:45} {n:>10,}")
        return

    print("rewriting TBox ...")
    dropped = rewrite_tbox(f"{args.src}/{P}_tbox.nt", f"{args.out}/{Q}_tbox.nt",
                           args.flip, report)
    print(f"  dropped {dropped} original axioms")

    for split in ("train", "valid", "test"):
        print(f"rewriting {split} ...")
        rewrite_tsv(f"{args.src}/{P}_{split}.tsv", f"{args.out}/{Q}_{split}.tsv",
                    flip_map, counts)

    # entity types are unaffected: flipping changes direction, not entities
    print("copying entity types (unchanged) ...")
    with open(f"{args.src}/{P}_entity_types.nt") as fin, \
         open(f"{args.out}/{Q}_entity_types.nt", "w") as fout:
        for line in fin:
            fout.write(line)

    if not args.skip_full_graph:
        print("rewriting full_graph.nt (large) ...")
        rewrite_nt(f"{args.src}/{P}_full_graph.nt", f"{args.out}/{Q}_full_graph.nt",
                   flip_map, counts)

    report.append("")
    report.append("triples flipped:")
    for r, n in counts.items():
        report.append(f"  {r:45} {n:>10,}")
    txt = "\n".join(report)
    with open("output/flip_report.txt", "w") as f:
        f.write(txt + "\n")
    print("\n" + txt)


if __name__ == "__main__":
    main()
