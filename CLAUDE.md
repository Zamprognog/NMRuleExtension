# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Rule-based link prediction for knowledge graphs, focused on **evaluating the impact of ontology-aware (semantic) grounding** on prediction quality.

Given a test triple `(s, p, o)`, the system grounds AMIE/AnyBURL rules against the KG to produce ranked candidate answers, then evaluates them with filtered MRR, Hits@k, and semantic consistency metrics. The central research question is whether replacing the standard grounding engine with a semantics-aware one (domain/range/disjointness constraints) improves both link prediction accuracy and the semantic validity of the top-ranked predictions.

**Data flow:**
1. `RuleRegistry` loads AMIE or AnyBURL rules from file
2. For each test triple, all matching rules are applied via `Rule.apply()` → `GroundingEngine`, collecting a `Map<String, List<Float>>` (candidate entity → confidence scores)
3. `RankingTree` ranks candidates lexicographically by their full confidence vector (multi-level tie-breaking)
4. `Evaluator` computes filtered MRR/Hits@k and semantic consistency (Sem@1/5/10/100)
5. `RunExperiment` runs the same rule set under both `GroundingEngine` and `SemanticGroundingEngine` and prints a side-by-side comparison

## Build & run

```sh
mvn clean install

# Run a full experiment (compares standard vs semantic engine on all rule sets)
mvn exec:java -Dexec.mainClass="ruleMiningSemanticExtension.RunExperiment" -Dexec.args="path/to/config.json [full|anyburl|amie]"

# Quick single-engine run
mvn exec:java -Dexec.mainClass="ruleMiningSemanticExtension.Main" -Dexec.args="path/to/config.json"

mvn test
```

Datasets are not in the repo — download from Zenodo (see README). Experiments are config-driven via JSON files (one per dataset). Datasets used: NELL995, OWL2Bench, Hetionet, CSKG2, YAGO4.5.

## Key architecture

| Package | Role |
|---|---|
| `groundingEngine/` | `GroundingEngine` (standard DFS path search), `SemanticGroundingEngine` (adds domain/range/disjointness filtering), `RuleRegistry`, `RankingTree` |
| `graphTools/` | RDF graph management via Apache Jena; `SemanticGraphManager` extracts and precomputes ontology constraints (domain, range, disjoint classes, functional properties) |
| `rules/` | Parsers and models for AMIE and AnyBURL rule formats; `Rule.apply()` produces `Map<String, List<Float>>` |
| `evaluation/` | `Evaluator` (filtered MRR, Hits@k, semantic consistency); `Metrics` value object |
| `domain/` | `GroundingResult`, `Triple`; no `PredictionCandidate` or feature extraction in this branch |
| `utils/` | `DataLoader`, `ExperimentConfig` (JSON config), `DualLogger` |

## The two grounding engines

**`GroundingEngine`** — standard BFS/DFS path and pattern search. No semantic filtering. Used as the baseline.

**`SemanticGroundingEngine`** — extends `GroundingEngine`, adds:
- Pre-query pruning: skips rules if the anchor entity already violates domain/range constraints
- Per-grounding filtering: rejects predicted entities that violate range (object prediction) or domain (subject prediction)
- Functional/inverse-functional property enforcement
- Disjoint class conflict detection

`RunExperiment.runRuleSetEvaluation()` always runs both engines on the same rule set and prints a comparison table.

## Semantic consistency metrics

`Evaluator.calculateMetrics()` tracks, for the top-1/5/10/100 ranked candidates:
- Whether the predicted entity violates range/domain constraints of the target relation
- Whether predicting it would violate a functional or inverse-functional property constraint

These are reported as Sem@1/5/10/100 (fraction of candidates that are semantically consistent).

## Extending the system

- **New dataset:** add a JSON config file mirroring an existing one; point to train/valid/test TSV files and optionally a schema OWL file + types file
- **New rule set:** add the path to `ExperimentConfig` and a new branch in `RunExperiment.runFullExperiment()`
- **New semantic constraint:** add extraction logic to `SemanticConstraintLoader` / `SemanticGraphManager`, then enforce in `SemanticGroundingEngine.checkSuccess()` or `isQueryValid()`
- **New ranking strategy:** replace or extend `RankingTree`; the `Evaluator` calls `tree.getFinalRanking(predictions)`
