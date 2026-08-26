# Data and rule sets for *Extending Mined Rules for Link Prediction with Schema-based Exceptions*

This is the README for the Zenodo deposit. Copy it into the archive root as `README.md`;
it is kept under version control here so it stays in step with the code.

---

This record contains the prepared knowledge graphs and the **mined rule sets** used to
produce every table in the paper. Code lives separately, at
<https://github.com/Zamprognog/NMRuleExtension>.

## Why this deposit exists

One class of artifact in this project cannot be regenerated: the **mined rule sets**.
AnyBURL is time-budgeted (`SNAPSHOTS_AT`) and multi-threaded (`WORKER_THREADS = 4`), so
re-running the miner on the same graph with the same configuration yields a *different*
rule set, and therefore different numbers in every table. Everything downstream —
materialized triples, violation counts, ranking metrics — is deterministic given the rules
and the graph.

So the rules are the keystone. With them, every published number can be recomputed; without
them, none can. That is what this record preserves.

## Contents

```
NMRuleExtension-data-v1.0/
  README.md               this file
  DATA_PREPARATION.md     log of manual, non-reproducible fixes applied after download
  NELL995/    data/ rules/ NELL995.json
  YAGO4.5/    data/ rules/ yago4.5.json
  CSKG2/      data/ rules/ CSKG2.json
  hetionet/   data/ rules/ hetionet.json
  YAGO4.5-10-flip/  data/ rules/ predictions_ifp/ configs/
```

Unpacking, from the repository root:

```sh
# the four main datasets
cp -r NELL995 YAGO4.5 CSKG2 hetionet  data/
cp    DATA_PREPARATION.md              data/

# the flip variant, which the code expects beside its scripts, not under data/
cp -r YAGO4.5-10-flip/data YAGO4.5-10-flip/rules YAGO4.5-10-flip/predictions_ifp \
      yago_flip_experiment/
```

Every config uses paths relative to the repository root, so nothing needs editing
afterwards. Note the flip variant is the one exception to the `data/` rule: its config
(`yago_flip_experiment/YAGO4.5-10-flipIFP.json`) points at
`yago_flip_experiment/data/`, alongside the scripts that build it.

### Per dataset

`data/` holds the three splits (`_train.tsv`, `_valid.tsv`, `_test.tsv`), the full graph
(`_full_graph.nt`, which includes `rdf:type` assertions), the schema (`_tbox.nt`, or
`NELL.ontology.ttl` for NELL995) and the entity types (`_entity_types.nt`).

`rules/` holds the four mined rule sets per dataset, named
`<dataset>_rules_anyburl_ALL-100`, `_anyburl_CP-100`, `_rules_amie_4CP.tsv` and
`_rules_amie_3CP.tsv`. These map onto the paper's columns as Full / CP for AnyBURL and
L4 / L3 for AMIE.

| dataset | full graph | train | valid | test |
|---|---:|---:|---:|---:|
| NELL995 | 205,038 | 113,500 | 14,188 | 14,188 |
| YAGO4.5-10 | 3,997,045 | 3,222,052 | 16,273 | 16,273 |
| hetionet | 2,275,313 | 2,205,193 | see note | 22,502 |
| CSKG-490K (CSKG2) | 632,931 | 335,016 | 59,121 | 98,535 |
| YAGO4.5-10-flip | 3,997,045 | 3,222,052 | 16,273 | 16,273 |

YAGO4.5-10-flip matches its base exactly, by construction: the flip rewrites triples without
creating or losing any. Graph sizes count all lines in the `.nt`, type assertions included. The paper's
`CSKG-490K` name refers instead to its 497,292 *relational* triples.

### YAGO4.5-10-flip

The derived variant used for the inverse-functionality analysis, archived in full: the same
six files as the other datasets, plus its mined rules and the materialization output they
produced. It is a deterministic rewrite of YAGO4.5-10 in which `schema:birthPlace` and
`schema:deathPlace` are replaced by inverse-functional inverses; `flip_yago.py` and
`verify_flip.py` in the code repository rebuild and re-check it, but you do not need to run
them to use this record.

Two things to know about the files. `_entity_types.nt` is byte-identical to YAGO4.5-10's —
flipping changes a statement's direction, not the entities in it — so it is duplicated here
purely so the folder stands alone. `_tbox.nt` is *not* identical: it is where the inverse
properties are declared, with domain and range swapped.

Note this rule set was mined with a lower confidence threshold (0.01 rather than 0.1) and a
longer snapshot (300s rather than 100s), restricted to the two inverse-functional heads.
Its absolute figures are therefore not comparable with the main tables; the comparison of
interest is standard vs. non-monotonic within that experiment.

## What is deliberately absent

- **Upstream sources and intermediates** (~10 GB for YAGO alone) — re-derivable from the
  official releases through the preprocessing recorded in `DATA_PREPARATION.md`.
- **Materialization output for the four main datasets** (~1.8 GB) — deterministic given the
  rules and the graph. The evaluation logs the published counts were read from are in the
  code repository.

## Known issues

`hetionet_validation.tsv` was rebuilt on 2026-08-26. The file previously shipped was a 10%
random sample of the whole edge set (225,019 triples, 98% of them also in train) rather than
hetionet's held-out validation fold. It is now the exact complement of train and test —
22,502 triples, disjoint from both — so train + validation + test = 2,250,197, the edge count
of Hetionet v1.0. All four datasets and the flip variant now have pairwise-disjoint splits.

`hetionet_full_graph.nt` has not been rebuilt to match. It was assembled from train + test +
the old validation file, so it contains 2,253 of the corrected validation triples and is
missing the other 20,249. The materialization results reported in the paper were computed
over the graph as it stands here.

## Verifying your copy

Each file's checksum is published in this record's Zenodo metadata; compare against that if
a download looks truncated.

## Provenance

Original sources, and the preprocessing applied to each, are documented per dataset in the
paper's supplementary material. Tool versions: AnyBURL 23-1, AMIE 3.5.1, Java 21.

## Citation and licence

Cite the accompanying paper. Data licences are those of the upstream sources — YAGO 4.5,
NELL, Hetionet and CSKG-2.0 respectively; this record redistributes prepared derivatives
for reproducibility and does not relicense them.
