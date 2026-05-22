# Technical Guide: Evolutionary Rule Aggregation

This document describes the evolutionary learning component of the project. The system uses **Genetic Programming (GP)** to discover an optimal re-scoring formula for link prediction candidates produced by rule grounding.

---

## 1. Architectural Overview

The goal is to replace fixed heuristic aggregation (e.g., max confidence) with a learned function that takes multiple features of a prediction candidate as input and outputs a single score used for ranking.

### Data Flow

```
Training KG
    │
    ▼
GroundingEngine  ──►  PredictionCandidate  ──►  FeatureExtractor  ──►  Double[11]
  (rule paths)          (subgraph, rules)         (one-time, before                │
                                                   evolution starts)               │
                                                                                   ▼
Validation set  ──►  ValidationQuery  ──────────────────────────────►  AggregatorFitnessFunction
  (grounded,          (entity, features[])                               (MRR on mini-batch)
   pre-computed)                                                                   │
                                                                                   ▼
                                                                         Jenetics GP Engine
                                                                          (evolves formula)
                                                                                   │
                                                                                   ▼
Test set  ──►  groundTriple (parallel)  ──►  filteredRank  ──►  MetricsTracker
                                                                  (MRR, H@1/3/10)
```

### Pipeline Phases (`EvolutionPipeline.run`)

| Phase | What happens |
|---|---|
| **1. Load** | Training facts loaded into `allKnownFacts`; KG parsed into `SemanticGraphManager`; rules loaded from AnyBURL file |
| **2. Prepare validation** | Up to `validationSize` triples sampled uniformly across predicates; grounded in both directions (`s,p,?` and `?,p,o`) in parallel; feature arrays pre-computed once for all candidates |
| **3. Evolve** | Jenetics GP engine maximises MRR on random mini-batches; stops when fitness is steady for 50 generations or the generation cap is reached |
| **4. Evaluate** | Test triples grounded in parallel; filtered rank computed for the evolved aggregator and a max-confidence baseline; MRR, H@1, H@3, H@10 printed |

---

## 2. Key Components

### `FeatureExtractor` (`aggregation.learning`)

Single source of truth for feature computation. Called once per candidate before evolution; the resulting `Double[]` arrays are stored in `ValidationQuery` and reused across all generations.

```java
FeatureExtractor.setSemanticGraphManager(sgm); // call once at startup
Double[] features = FeatureExtractor.extract(candidate);
```

**Feature vector — `FEATURE_COUNT = 10`:**

| Index | Name | Description |
|---|---|---|
| 0 | `max` | Highest rule confidence |
| 1 | `noisyOr` | `1 − ∏(1 − conf_i)` — probabilistic union |
| 2 | `ruleCount` | `log(1 + n_rules)` — log-scaled count of distinct rules |
| 3 | `mean` | Mean rule confidence |
| 4 | `totalGroundings` | `log(1 + n_groundings)` — log-scaled total grounding count |
| 5 | `subgraphSize` | `log(1 + n_triples)` — log-scaled supporting subgraph size |
| 6 | `avgRuleLength` | Mean body length of supporting rules |
| 7 | `density` | Edge density of the grounding subgraph |
| 8 | `avgDegree` | Average node degree in the grounding subgraph |
| 9 | `domainRangeMatch` | 1.0 if candidate satisfies TBox domain (for `?,p,o`) or range (for `s,p,?`) constraint, else 0.0 |

### `HybridAggregator` (`aggregation.learning`) — *primary aggregator*

Combines two evolved components in a single genotype:
- **Chromosome 0 — `ProgramChromosome`:** an arithmetic expression tree (the formula structure)
- **Chromosome 1 — `DoubleChromosome`:** a pool of 20 learnable numeric weights (`w0`–`w19`)

The tree has access to all 11 feature terminals plus the 20 weight terminals (31 inputs total). This allows the GP to both discover formula structure and fine-tune numeric coefficients simultaneously.

**Operations available:** `ADD`, `SUB`, `MUL`, `SAFE_DIV`, `IF`

`IF(condition, then, else)` — returns `then` if `condition > 0`, otherwise `else`. Example evolved formula:
```
if(rangeMatch - 0.5, mul(noisyOr, w3), max)
```
meaning: "if rangeMatch > 0.5, weight noisyOr by w3; otherwise fall back to max confidence."

> **Note on raw types:** `HybridAggregator` suppresses raw-type warnings because Jenetics requires all chromosomes in a `Genotype<G>` to share the same gene type `G`. The two-chromosome design is not expressible with the typed API, so raw types are a deliberate trade-off, not an oversight.

### `SymbolicAggregator` (`aggregation.learning`) — *alternative*

Pure GP aggregator: one `ProgramChromosome` over the 11 feature terminals only — no weight pool. Simpler genotype, faster evaluation. Use it when you want to find interpretable formulas without numeric coefficients.

**Operations:** same as `HybridAggregator` — `ADD`, `SUB`, `MUL`, `DIV`, `IF`.

### `LinearCombinationAggregator` (`aggregation.learning`) — *GA baseline*

Uses a standard Genetic Algorithm (not GP): evolves a weight vector `w[0..10]` and computes `Σ w_i × feature_i`. No tree structure. Useful as a simpler baseline to compare against the GP aggregators.

### `AggregatorFitnessFunction` (`aggregation.learning`)

Evaluates a genotype's fitness on the validation set.

- Takes pre-computed `ValidationQuery` objects (entity → `Double[]` pairs) — no feature recomputation during evolution.
- **Mini-batch sampling:** each call draws `batchSize` random queries using partial Fisher-Yates, so each individual sees a different subset. Acts as regularization and speeds up per-generation evaluation.
- **O(n) rank computation:** finds the correct entity's rank by counting candidates with strictly higher scores — no sort required.
- Uses `aggregator.newInstance()` instead of reflection for thread-safe parallel evaluation.

```java
// Fitness = average MRR on the sampled batch
double fitness = totalMrr / batch.size();
```

### `EvolvableAggregator` (interface)

All aggregators implement this interface:

```java
void configure(Genotype<G> genotype);        // load evolved parameters
double aggregate(PredictionCandidate c);     // score via full feature extraction (inference/test time)
double scoreFeatures(Double[] features);     // score from pre-computed array (used during evolution)
EvolvableAggregator<G> newInstance();        // return fresh unconfigured instance (avoids reflection)
Factory<Genotype<G>> getGenotypeFactory();   // define the search space
```

---

## 3. Evolution Engine Configuration

All engine parameters are set in `EvolutionPipeline.evolveBestAggregator`. Current defaults:

| Parameter | Value | Where to change |
|---|---|---|
| Max tree depth | 5 | `ProgramChromosome.of(5, ...)` in `getGenotypeFactory()` |
| Weight pool size | 20 | `WEIGHT_POOL_SIZE` in `HybridAggregator` |
| Population size | 50 (Jenetics default) | `.populationSize(n)` on `Engine.builder` |
| Offspring fraction | 0.6 (Jenetics default) | `.offspringFraction(d)` on `Engine.builder` |
| Selection | Tournament (Jenetics default) | `.selector(new TournamentSelector<>(k))` |
| Mutation/crossover | Jenetics defaults | `.alterers(new Mutator<>(p), ...)` |
| Steady-fitness window | 50 generations | `Limits.bySteadyFitness(50)` |
| Hard generation cap | 200 | `generations` param in `run()` / `main()` |
| Mini-batch size | 200 queries | `batchSize` param in `run()` / `main()` |
| Validation set size | 2500 triples | `validationSize` param in `run()` / `main()` |

The engine stops when **either** the steady-fitness condition **or** the generation cap is met.

---

## 4. How to Modify and Extend

### Adding a new feature

1. **`FeatureExtractor.extract()`** — compute the new value and append it to the returned array.
2. **`FeatureExtractor.FEATURE_COUNT`** — increment from 10 to 11.
3. **`SymbolicAggregator.TERMINALS`** and **`HybridAggregator.TERMINALS`** — add `Var.of("myFeature", 10)`.

The `LinearCombinationAggregator` weights array and `HybridAggregator.TOTAL_INPUTS` derive from `FEATURE_COUNT` automatically.

### Adding a new GP operation

In `SymbolicAggregator.OPERATIONS` and/or `HybridAggregator.OPERATIONS`:

```java
// Custom protected division (avoids Infinity from DIV by zero)
Op<Double> SAFE_DIV = Op.of("sdiv", 2, v -> Math.abs(v[1]) < 1e-9 ? 0.0 : v[0] / v[1]);

// Square root (always positive input — use with care)
// MathOp.SQRT is available from Jenetics
```

More operations increase the search space — consider reducing max tree depth to compensate.

### Changing the fitness metric

In `AggregatorFitnessFunction.scoreQuery`, replace the MRR calculation:

```java
// Current: MRR
return 1.0 / rank;

// Alternative: Hits@10
return rank <= 10 ? 1.0 : 0.0;

// Alternative: Hits@1
return rank == 1 ? 1.0 : 0.0;
```

### Switching to a different aggregator

In `EvolutionPipeline.run`, replace `HybridAggregator` with `SymbolicAggregator` or `LinearCombinationAggregator`:

```java
// Pure GP (no weight pool):
SymbolicAggregator bestAggregator = new SymbolicAggregator();

// Linear GA baseline:
LinearCombinationAggregator bestAggregator = new LinearCombinationAggregator();
```

The rest of the pipeline is aggregator-agnostic through the `EvolvableAggregator` interface.

---

## 5. Parallelism and Thread Safety

| Operation | Parallelism | Notes |
|---|---|---|
| Validation set grounding | `parallelStream` | `GroundingEngine` is read-only after `finalizeGraph()`; each thread creates its own `predictions` map |
| Fitness evaluation | Jenetics parallel engine | Each individual gets a fresh aggregator via `newInstance()`; no shared mutable state |
| Test set scoring | `parallelStream` | `filteredRank` reads only `allKnownFacts` (read-only `HashSet`) and the configured aggregator (read-only post-configuration) |
| Metrics aggregation | Sequential | Collected after parallel phase; plain `MetricsTracker` — not thread-safe by design |

---

## 6. Interpreting Results

The pipeline prints the best formula as a parenthesised expression:

```
Best Evolved Formula: if(rangeMatch, mul(noisyOr, w2), add(max, mul(density, w7)))
```

Reading it:
- **`max` / `noisyOr` prominent** → the system relies mainly on rule confidence statistics.
- **`density` / `avgDegree` prominent** → structural subgraph evidence matters for this relation type.
- **`rangeMatch` in an `if` branch** → the system learned that TBox schema constraints are a strong binary filter; it uses a different scoring strategy depending on whether the candidate satisfies the range.
- **`wN` coefficients** → numeric weights learned by the GA component; inspect `bestAggregator.toString()` to see their resolved values.

Test set results report both the evolved aggregator and a max-confidence baseline side by side:

```
Results for Best Evolved Aggregator:
MRR:  0.3421
H@1:  0.2310
H@3:  0.4102
H@10: 0.5870
Total queries: 4832

Results for Baseline (Max Confidence):
MRR:  0.2891
...
```

Evaluation uses the standard **filtered** setting: when ranking candidates for `(s, p, ?)`, known true completions other than the target are excluded from the rank count.
