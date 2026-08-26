# YAGO4.5-10-flip

A YAGO4.5-10 variant in which the usable functional properties are **replaced by
their inverse**, turning them into inverse-functional properties with no
inverse-twin in the data to leak from.

Originals in `data/YAGO4.5/` are never modified. Scripts, configs and logs are tracked;
`data/`, `rules/`, `predictions/` and `predictions_ifp/` are gitignored (see below).

## Why

In NELL the inverse-functional properties come bundled with their functional
twins in the data (`haswife`/`wifeof`, `acquired`/`acquiredby` all occur), so a
rule can invert one into the other for free. Any measured benefit of semantic
grounding is confounded by that leakage. This dataset removes the confound by
construction: the flipped property exists, its original does not, and no
`owl:inverseOf` links them.

**The control is the original YAGO4.5-10** -- same facts, same entities, only the
direction reversed. That is a cleaner control than a sibling property, because it
holds domain, range and data volume fixed.

## What was flipped

YAGO4.5-10 declares four functional properties. All four are perfectly functional
in the data (fun = 1.000, zero violations), but only two are usable:

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

Flipping is `(s, P, o) -> (o, P-inverse, s)`, with the inverse minted in the YAGO
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

## Verification

`python3 verify_flip.py` -- all checks pass:

1. **counts conserved** per split (train 3,222,052 -> 3,222,052; birthPlace
   139,324 -> birthPlaceOf 139,324; deathPlace 55,301 -> deathPlaceOf 55,301)
2. **empirically inverse functional**: both at ifun = 1.000, zero objects with
   more than one subject
3. **originals gone**: 0 occurrences of `schema:birthPlace` / `schema:deathPlace`
4. **domain/range hold**: 0 violations in 196,162 flipped triples, checked
   against `entity_types.nt` through the TBox `subClassOf` closure
5. **no new leakage** (below)

Separately, `FindInverseFunctional` on the new TBox lists both properties under
**INFERRED by OWL_MEM_MICRO_RULE_INF** -- the exact reasoner path
`SemanticConstraintLoader` uses -- so `SemanticGroundingEngine` enforces them.
The same tool on the original TBox reports 0, confirming the control is clean.

## Leakage audit

The flip introduces none (worst cross-relation pair unchanged at 71.0%). But the
audit surfaced **pre-existing leakage in YAGO4.5-10 that has nothing to do with
this experiment**:

| pair | co-occurrence | share |
|---|---|---|
| `capital` <-> `location` | 5,734 | **71.0% of `capital`** |
| `notableWork` <-> `actor` | 2,097 | **53.1% of `notableWork`** |
| `neighbors` <-> `neighbors` | 463,110 | 95.6% (symmetric, expected) |
| `spouse` <-> `spouse` | 13,924 | 96.8% (symmetric, expected) |

`capital(France, Paris)` co-occurs with `location(Paris, France)` 71% of the
time. That is a near-inverse pair a rule learner will exploit, and it affects
**existing YAGO4.5-10 results**, not just this variant. Worth checking before
trusting published numbers on that dataset.

Post-flip, `birthPlaceOf` <-> `homeLocation` sits at 2.0% -- small enough to
ignore, but noted.

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

## Caveats

- Two constrained relations, not 131. Per-relation statistics are solid (140k and
  56k triples) but relation *diversity* is limited: this is a controlled probe.
  (An earlier NELL-based route was explored and dropped; see the paper's Section
  5.1.1 for why a derived dataset was needed at all.)
- Flipping swaps head- and tail-prediction, so the candidate spaces differ
  (20,393 places vs 140,221 people). Compare flipped-vs-original on the same
  facts, not raw MRR across directions.
- No usable functional property remains inside the dataset, by design -- the
  control lives in the original.

## AnyBURL mining

`java -Xmx6G -cp ../tools/AnyBURL_og.jar de.unima.ki.anyburl.Learn config-learn-flip.properties`

**Heap matters here.** This machine has 16 GB of physical RAM; `-Xmx16G` lets the
JVM grow its heap to all of it, and on 3.2M triples macOS kills the process before
the JVM reaches its own limit -- so you get a silent death with no
`OutOfMemoryError` in the log. Use `-Xmx6G`; actual RSS peaks around 2.5 GB.

Result (`rules/YAGO4.5-10-flip_rules_anyburl_ALL-300`), 19,490 rules total:

| head relation | rules |
|---|---|
| `birthPlaceOf` | 104 |
| `deathPlaceOf` | 58 |

Examples:

```
0.341  birthPlaceOf(Copenhagen,Y) <= deathPlaceOf(Copenhagen,Y)
0.132  deathPlaceOf(X,Y)          <= deathPlaceOf(X,A), children(Y,A)
0.154  deathPlaceOf(Buenos_Aires,Y) <= homeLocation(Y,Buenos_Aires)
```

Zero rules reference `schema:birthPlace` / `schema:deathPlace`, confirming the
originals are gone from the mined vocabulary too.

**`SNAPSHOTS_AT` is not a useful early-exit knob on this graph.** AnyBURL checks
the snapshot threshold only *between* batches, and batches here run far longer
than `BATCH_TIME = 5000`. With `SNAPSHOTS_AT = 100,300` both thresholds were
crossed inside a single batch:

```
>>> CREATING SNAPSHOT 0 after 1469 seconds
>>> CREATING SNAPSHOT 1 after 1469 seconds
```

so `-100` and `-300` are near-duplicates (19,483 vs 19,490 rules) and the run
still took 24 minutes. To actually shorten it, reduce `MAX_LENGTH_CYCLIC` (3 -> 2)
or `BATCH_TIME`. AnyBURL also exits with status 1 after writing rules; the output
is complete regardless.

## The rule set the paper reports on

The unrestricted run above is **not** what Tables 8 and 9 use. Its rules with
`birthPlaceOf` / `deathPlaceOf` heads are individually low-confidence and are almost
entirely absent from the top-1/5/10% of the sorted rule set — across 18 files and 5.9M
inferred triples the flipped relations appeared 258 times, all in the FULL-10% files — so
the constraint would have gone essentially untested.

`config-learn-flip-IFP.properties` fixes that by restricting mining to the two
inverse-functional heads, so every mined rule, and therefore every materialized triple,
is subject to the constraint under study:

```
SINGLE_RELATIONS = <...>/birthPlaceOf,<...>/deathPlaceOf
THRESHOLD_CONFIDENCE = 0.01      # main datasets use 0.1
SNAPSHOTS_AT = 300               # main datasets use 100
```

20,562 rules. Both deviations are deliberate and are stated in the paper. They mean the
absolute figures here are **not** comparable with the main tables — the comparison of
interest is standard vs. nmr *within* this experiment, which shares one rule set.

Two `SINGLE_RELATIONS` traps, both load-bearing: the value is split on `,` with no
trimming (`IOHelper.getProperty`), so no spaces around the comma; and `SAFE_PREFIX_MODE`
must stay `false`, or AnyBURL prefixes relations with `r` internally and these plain URIs
match nothing.

## Rebuild and verify

```sh
python3 flip_yago.py       # needs prepared ../data/YAGO4.5/data/ as input
python3 verify_flip.py     # five checks; non-zero exit on any failure
```

The generated data is ~1.2 GB and is deliberately **not** archived: it is a deterministic
rewrite of YAGO4.5-10, so rebuilding it is the intended route. `verify_flip.py` confirms the
rebuild satisfies every property the experiment depends on — counts conserved, both
properties empirically inverse functional, originals gone, domain/range clean, no new
leakage — though it does not compare byte-for-byte against the original build.

The mined rules are the opposite case: AnyBURL is time-budgeted (`SNAPSHOTS_AT`) and
multi-threaded (`WORKER_THREADS = 4`), so re-mining yields a *different* rule set and
different numbers. `rules/` is gitignored and ships in the Zenodo snapshot instead.

## Where the published numbers come from

| paper | source |
|---|---|
| Table 8/9 violation counts | `logs/mateval_ifp.log`, `logs/mateval_ifp_std5.log` |
| applied / target rule counts, timings | breakdown blocks in `logs/mat_ifp.log` |

Those logs are tracked, so the numbers can be checked without rerunning anything.
