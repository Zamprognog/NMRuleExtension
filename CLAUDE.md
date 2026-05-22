# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Rule-based link prediction for knowledge graphs. Given a query `(s, p, ?)` or `(?, p, o)`, the system generates candidate answers by grounding logical rules (AMIE / AnyBURL format) against the KG and then **re-scores** those candidates using a learned aggregation formula.

The key idea: instead of using a fixed aggregation heuristic (e.g., max confidence), the system uses **Genetic Programming** (via Jenetics.prog) to evolve an arithmetic expression tree that combines rule and graph features into a final re-scoring function. This preserves symbolic interpretability while learning from data.

**Data flow:**
1. `GroundingEngine` finds all rule groundings supporting each candidate
2. Features are extracted from `PredictionCandidate` (rule confidences, subgraph density, semantic range matches, etc. — 10 terminals total)
3. `SymbolicAggregator` evaluates the evolved expression tree over those features
4. Candidates are ranked by score; evaluated with MRR and Hits@k

**Fitness function:** MRR on a validation set. The best formula is then evaluated on the test set.

## Build & run

```sh
mvn clean install

# Run the evolution pipeline
mvn exec:java -Dexec.mainClass="ruleMiningSemanticExtension.aggregation.learning.EvolutionPipeline" -Dexec.args="data/NELL995/NELL995.json"

# Run a full experiment (AMIE + AnyBURL, or anyburl / amie mode)
mvn exec:java -Dexec.mainClass="ruleMiningSemanticExtension.RunExperiment" -Dexec.args="path/to/config.json [full|anyburl|amie]"

mvn test
```

Datasets are not in the repo — download from Zenodo (see README). Experiments are fully config-driven via JSON files (one per dataset).

## Key architecture

| Package | Role |
|---|---|
| `aggregation/learning/` | GP pipeline: `EvolutionPipeline`, `SymbolicAggregator`, `AggregatorFitnessFunction` |
| `groundingEngine/` | Rule grounding; `SemanticGroundingEngine` adds ontology-aware filtering (see note below) |
| `graphTools/` | RDF graph management via Apache Jena; extracts domain/range/disjointness constraints |
| `rules/` | Parsers and models for AMIE and AnyBURL rule formats |
| `evaluation/` | MRR, Hits@k, semantic consistency metrics |
| `domain/` | `PredictionCandidate` (groundings + integer subgraph stats), `GroundingResult`, `Triple` |

## Subgraph features

`PredictionCandidate` tracks the 1-hop neighborhood of all grounding entities as integer counts (no Triple objects) for performance:
- `subgraphEdgeCount` — total edges across all groundings
- `subgraphNodeIds` — unique entity integer IDs in the neighborhood

`FeatureExtractor` reads `getSubgraphEdgeCount()` and `getSubgraphNodeCount()` directly; no iteration needed. See `EVOLUTION_GUIDE.md` for GP internals.

## Unused / out-of-scope code

Parts of the codebase implement a **semantic-based materialization strategy** — inferring new triples from ontology constraints rather than just re-scoring existing candidates. The primary example is `SemanticGroundingEngine` and related logic in `graphTools/` and `materialization/`. This functionality is **not part of the current project** and should not be assumed to be active or integrated. Don't try to wire it into the GP pipeline or evaluation flow unless explicitly asked.

## Extending the system

- **New feature:** add to `FeatureExtractor.extract()` and update `FEATURE_COUNT`
- **New GP operation:** add to `SymbolicAggregator.OPERATIONS` (increases search space — may need more generations)
- **Different fitness metric:** modify `AggregatorFitnessFunction.scoreQuery` (e.g., Hits@10 instead of MRR)
- **Detailed GP internals:** see `EVOLUTION_GUIDE.md`
