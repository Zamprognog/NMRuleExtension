#!/usr/bin/env python3
"""
Verification for YAGO4.5-10-flip. Checks, in order:

  1. triple counts conserved per split (flip must not create or lose facts)
  2. the flipped properties are empirically inverse functional (ifun = 1.000)
  3. the ORIGINAL property names are gone from the data
  4. domain/range hold for the flipped triples, using the TBox subClassOf closure
  5. leakage: no relation pair (r1, r2) where (s,r1,o) systematically co-occurs
     with (o,r2,s) -- run on both the original and the flipped dataset
  6. checksums: the generated files match CHECKSUMS.md

The generated data is ~1.2 GB and is therefore not archived: it is rebuilt from
the prepared YAGO4.5-10 by flip_yago.py, and check 6 is what tells a reader that
their rebuild is byte-identical to the one the paper reports on. Run with
--write-manifest to (re)generate CHECKSUMS.md after a deliberate rebuild.

Exit code is non-zero if any check fails.
"""
import collections, hashlib, sys, os

SRC = "../data/YAGO4.5/data"
OUT = "data"
P, Q = "YAGO4.5-10", "YAGO4.5-10-flip"
SCHEMA = "http://schema.org/"
YAGO = "http://yago-knowledge.org/resource/"
FLIPS = {SCHEMA + "birthPlace": YAGO + "birthPlaceOf",
         SCHEMA + "deathPlace": YAGO + "deathPlaceOf"}
SUBCLASS = "http://www.w3.org/2000/01/rdf-schema#subClassOf"
RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain"
RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range"

fails = []


def ok(cond, msg):
    print(("  PASS  " if cond else "  FAIL  ") + msg)
    if not cond:
        fails.append(msg)


def load_tsv(path):
    for line in open(path):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3:
            yield p[0], p[1], p[2]


def nt_terms(line):
    t = line.rstrip("\n").rstrip()
    if not t.endswith(".") or not t.startswith("<"):
        return None
    body = t[:-1].strip()
    try:
        i = body.index(">") + 1
        s = body[1:i - 1]
        rest = body[i:].lstrip()
        j = rest.index(">") + 1
        p = rest[1:j - 1]
        o = rest[j:].strip()
    except ValueError:
        return None
    return s, p, (o[1:-1] if o.startswith("<") and o.endswith(">") else o)


# ---- 1. counts conserved --------------------------------------------------
print("\n[1] triple counts conserved per split")
for split in ("train", "valid", "test"):
    a = collections.Counter(r for _, r, _ in load_tsv(f"{SRC}/{P}_{split}.tsv"))
    b = collections.Counter(r for _, r, _ in load_tsv(f"{OUT}/{Q}_{split}.tsv"))
    ok(sum(a.values()) == sum(b.values()),
       f"{split}: {sum(a.values()):,} -> {sum(b.values()):,} total")
    for orig, inv in FLIPS.items():
        ok(a[orig] == b[inv],
           f"{split}: {orig.rsplit('/',1)[-1]} {a[orig]:,} -> {inv.rsplit('/',1)[-1]} {b[inv]:,}")

# ---- 2/3. empirical inverse functionality, originals gone -----------------
print("\n[2] flipped properties are empirically inverse functional")
o2s = collections.defaultdict(lambda: collections.defaultdict(set))
seen_orig = collections.Counter()
allt = []
for split in ("train", "valid", "test"):
    for s, r, o in load_tsv(f"{OUT}/{Q}_{split}.tsv"):
        allt.append((s, r, o))
        if r in FLIPS.values():
            o2s[r][o].add(s)
        if r in FLIPS:
            seen_orig[r] += 1
for inv in FLIPS.values():
    objs = o2s[inv]
    viol = sum(1 for _, ss in objs.items() if len(ss) > 1)
    n = sum(len(ss) for ss in objs.values())
    ok(viol == 0, f"{inv.rsplit('/',1)[-1]}: {len(objs):,} objects, "
                  f"{viol} with >1 subject  (ifun={len(objs)/max(n,1):.3f})")

print("\n[3] original property names absent from the flipped data")
for orig in FLIPS:
    ok(seen_orig[orig] == 0, f"{orig.rsplit('/',1)[-1]}: {seen_orig[orig]} occurrences remain")

# ---- 4. domain/range ------------------------------------------------------
print("\n[4] domain/range hold for flipped triples")
sup = collections.defaultdict(set)
declared = {}
for line in open(f"{OUT}/{Q}_tbox.nt"):
    t = nt_terms(line)
    if not t:
        continue
    s, p, o = t
    if p == SUBCLASS:
        sup[s].add(o)
    elif p in (RDFS_DOMAIN, RDFS_RANGE) and s in FLIPS.values():
        declared.setdefault(s, {})[p] = o


def closure(c, _cache={}):
    if c in _cache:
        return _cache[c]
    seen, stack = {c}, [c]
    while stack:
        for x in sup.get(stack.pop(), ()):
            if x not in seen:
                seen.add(x); stack.append(x)
    _cache[c] = seen
    return seen


types = collections.defaultdict(set)
for line in open(f"{OUT}/{Q}_entity_types.nt"):
    t = nt_terms(line)
    if t and t[1] == RDF_TYPE:
        types[t[0]].add(t[2])

for inv in FLIPS.values():
    d = declared.get(inv, {})
    dom, rng = d.get(RDFS_DOMAIN), d.get(RDFS_RANGE)
    bad_d = bad_o = checked = 0
    for s, r, o in allt:
        if r != inv:
            continue
        checked += 1
        if dom and types.get(s):
            if not any(dom in closure(c) for c in types[s]):
                bad_d += 1
        if rng and types.get(o):
            if not any(rng in closure(c) for c in types[o]):
                bad_o += 1
    name = inv.rsplit("/", 1)[-1]
    pct_d, pct_o = 100 * bad_d / max(checked, 1), 100 * bad_o / max(checked, 1)
    print(f"       {name}: domain={dom.rsplit('/',1)[-1] if dom else '-'} "
          f"range={rng.rsplit('/',1)[-1] if rng else '-'}  checked {checked:,}")
    ok(pct_d < 5, f"{name}: {bad_d:,} domain violations ({pct_d:.2f}%)")
    ok(pct_o < 5, f"{name}: {bad_o:,} range violations ({pct_o:.2f}%)")

# ---- 5. leakage -----------------------------------------------------------
print("\n[5] inverse-twin leakage (top pairs by co-occurrence)")


def leakage(paths, label):
    pair = collections.defaultdict(set)
    tot = collections.Counter()
    for p_ in paths:
        for s, r, o in load_tsv(p_):
            pair[(s, o)].add(r)
            tot[r] += 1
    co = collections.Counter()
    for (s, o), rs in pair.items():
        back = pair.get((o, s))
        if not back:
            continue
        for r1 in rs:
            for r2 in back:
                co[(r1, r2)] += 1
    print(f"  -- {label}")
    worst = 0.0
    for (r1, r2), c in co.most_common(6):
        frac = c / max(tot[r1], 1)
        worst = max(worst, frac) if r1 != r2 else worst
        print(f"     {r1.rsplit('/',1)[-1]:22} <-> {r2.rsplit('/',1)[-1]:22} "
              f"{c:>8,}  ({100*frac:5.1f}% of {r1.rsplit('/',1)[-1]})")
    if not co:
        print("     (no reciprocal pairs at all)")
    return worst


orig_paths = [f"{SRC}/{P}_{s}.tsv" for s in ("train", "valid", "test")]
flip_paths = [f"{OUT}/{Q}_{s}.tsv" for s in ("train", "valid", "test")]
w_orig = leakage(orig_paths, "ORIGINAL YAGO4.5-10 (baseline)")
w_flip = leakage(flip_paths, "FLIPPED YAGO4.5-10-flip")
ok(w_flip <= max(w_orig, 0.01) + 1e-9,
   f"flip introduces no new reciprocal leakage (worst cross-relation: "
   f"orig {100*w_orig:.1f}% -> flip {100*w_flip:.1f}%)")

# ---- 6. checksums of the generated files ----------------------------------
GENERATED = [f"{Q}_{s}.tsv" for s in ("train", "valid", "test")] + \
            [f"{Q}_{s}.nt" for s in ("full_graph", "entity_types", "tbox")]
MANIFEST = "CHECKSUMS.md"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


print("\n[6] checksums of the generated files")
digests = {}
for name in GENERATED:
    path = f"{OUT}/{name}"
    if os.path.exists(path):
        digests[name] = (sha256(path), os.path.getsize(path))
    else:
        ok(False, f"{name}: missing")

if "--write-manifest" in sys.argv:
    with open(MANIFEST, "w") as fh:
        fh.write("# YAGO4.5-10-flip generated files\n\n")
        fh.write("Written by `verify_flip.py --write-manifest`. These files are rebuilt by\n")
        fh.write("`flip_yago.py` rather than archived; run `verify_flip.py` to check a rebuild\n")
        fh.write("against this manifest.\n\n")
        fh.write("| file | bytes | sha256 |\n|---|---:|---|\n")
        for name in GENERATED:
            if name in digests:
                d, n = digests[name]
                fh.write(f"| `{name}` | {n:,} | `{d}` |\n")
    print(f"  WROTE  {MANIFEST} ({len(digests)} files)")
elif os.path.exists(MANIFEST):
    expected = {}
    for line in open(MANIFEST):
        parts = [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
        if len(parts) == 3 and parts[0].endswith((".tsv", ".nt")):
            expected[parts[0]] = parts[2]
    ok(bool(expected), f"{MANIFEST} lists at least one file")
    for name, (digest, _) in digests.items():
        if name in expected:
            ok(digest == expected[name], f"{name}: sha256 matches {MANIFEST}")
        else:
            ok(False, f"{name}: not listed in {MANIFEST}")
else:
    print(f"  SKIP   no {MANIFEST}; run with --write-manifest to create it")

print("\n" + ("ALL CHECKS PASSED" if not fails else f"{len(fails)} CHECK(S) FAILED"))
for f in fails:
    print("  - " + f)
sys.exit(1 if fails else 0)
