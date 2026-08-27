# YAGO4.5-10-flip

A YAGO4.5-10 variant in which the usable functional properties are **replaced by
their inverse**, turning them into inverse-functional properties with no
inverse-twin in the data to leak from.

Originals in `data/YAGO4.5/` are never modified. Scripts, configs and logs are tracked;
`data/`, `rules/`, `predictions/` and `predictions_ifp/` are gitignored (see below).


## What was flipped

YAGO4.5-10 declares four functional properties, but only two are usable:

| property | triples | distinct objects | domain -> range | action |
|---|---|---|---|---|
| `schema:birthPlace` | 140,221 | 20,393 | Person -> Place | **flipped** |
| `schema:deathPlace` | 55,941 | 8,610 | Person -> Place | **flipped** |
| `schema:gender` | 159,720 | **9** | Person -> Gender | kept (degenerate) |
| `schema:publisher` | **16** | 9 | *blank nodes* | kept (unusable) |

`gender` flipped would give `genderOf(Male, ?)` -- ~160k candidates per query.
`publisher` has 16 triples and blank-node domain/range, which
`SemanticConstraintLoader` skips (`!p.getDomain().isAnon()`), so it carries no
enforceable constraint.

Flipping is `(s, r, o) -> (o, r-inverse, s)`, with the inverse minted in the YAGO
namespace (schema.org does not define these terms):

```
schema:birthPlace  ->  yago:birthPlaceOf   [owl:InverseFunctionalProperty]
schema:deathPlace  ->  yago:deathPlaceOf   [owl:InverseFunctionalProperty]
```

TBox: the original axioms are dropped and the inverse is declared with
**domain and range swapped** (`domain Place`, `range Person`). Getting that swap
wrong is the one silent failure mode -- the semantic engine would reject every
flipped triple as a range violation and zero out the treatment arm.

No `owl:inverseOf` is emitted. The original no longer exists, and asserting the
link would recreate exactly the twin this dataset exists to avoid.


## Files

| file | role |
|---|---|
| `flip_yago.py` | builds the dataset (`--only-full-graph`, `--skip-full-graph`, `--flip`) |
| `verify_flip.py` | the five checks above; non-zero exit on failure |
| `data/` | the flipped dataset |
| `YAGO4.5-10-flip.json` | dataset config (paths relative to repo root) |
| `config-learn-flip.properties` | AnyBURL learn config, unrestricted (provenance only) |
| `config-learn-flip-CP.properties` | as above, closed-path rules only (provenance only) |
| `config-learn-flip-IFP.properties` | **the config the paper uses** — see below |
| `rules/`, `predictions/`, `logs/`, `output/` | outputs |


## AnyBURL mining

`java -Xmx6G -cp ../tools/AnyBURL_og.jar de.unima.ki.anyburl.Learn config-learn-flip.properties`


Result (`rules/YAGO4.5-10-flip_rules_anyburl_ALL-300`), 19,490 rules total:

Examples:

```
0.341  birthPlaceOf(Copenhagen,Y) <= deathPlaceOf(Copenhagen,Y)
0.132  deathPlaceOf(X,Y)          <= deathPlaceOf(X,A), children(Y,A)
0.154  deathPlaceOf(Buenos_Aires,Y) <= homeLocation(Y,Buenos_Aires)
```