# Data and rule sets for *Extending Mined Rules for Link Prediction with Schema-based Exceptions*

This record contains the prepared knowledge graphs and the **mined rule sets** used to
produce every table in the paper. Code lives separately, at
<https://github.com/Zamprognog/NMRuleExtension>.

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

Every config uses paths relative to the repository root. 
Note the flip variant is the one exception: its config
(`yago_flip_experiment/YAGO4.5-10-flipIFP.json`) points at
`yago_flip_experiment/data/`, alongside the scripts that build it, as it was intended as a separate, self-contained
experiment.

### Per dataset

`data/` holds the three splits (`_train.tsv`, `_valid.tsv`, `_test.tsv`), the full graph
(`_full_graph.nt`, which includes `rdf:type` assertions), the schema (`_tbox.nt`, or
`NELL.ontology.ttl` for NELL995) and the entity types (`_entity_types.nt`).

`rules/` holds the four mined rule sets per dataset, named
`<dataset>_rules_anyburl_ALL-100`, `_anyburl_CP-100`, `_rules_amie_4CP.tsv` and
`_rules_amie_3CP.tsv`. These map onto the paper's columns as Full / CP for AnyBURL and
L4 / L3 for AMIE. As described in the paper, L4 does not exist for CSKG2.

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
`verify_flip.py` in the code repository rebuild and re-check it.

Two things to know about the files. `_entity_types.nt` is duplicated here
purely so the folder is self-contained. `_tbox.nt` is instead different: it is where the inverse
properties are declared, with domain and range swapped.

## Provenance

Original sources, and the preprocessing applied to each, are documented per dataset in the
paper's supplementary material. Tool versions: AnyBURL 23-1, AMIE 3.5.1, Java 21.
