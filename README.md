# Non-monotonic exceptions

This project is a Java-based framework for Rule-Based Knowledge Graph Completion with a focus on **Semantic Consistency**. It provides tools to evaluate rules (e.g., from AnyBURL or AMIE) and perform Knowledge Graph materialization while enforcing schema-based constraints.

## Features

-   **Rule Evaluation**: Evaluate rule sets on test data with standard and semantic grounding engines.
-   **Semantic Consistency**: Automated checks for domain, range, and functional constraints during prediction.
-   **Knowledge Graph Materialization**: Expand your graph by applying rules until a certain triple count or rule confidence threshold is reached.
-   **Support for Multiple Rule formats**: Native support for AnyBURL and AMIE rule formats.
-   **Dataset Agnostic**: Easy configuration for various datasets (NELL, Hetionet, YAGO, CSKG2).

## Prerequisites

-   Java 11 or higher
-   Maven

## Project Structure

-   `src/main/java/evolveAggregation/`: Core logic and entry points.
    -   `RunExperiment`: Main class for running link prediction experiments.
    -   `RunMaterialization`: Main class for large-scale graph materialization.
    -   `DebugMaterialization`: Helper class for targeted materialization scenarios.
    -   `evaluation/`: Metrics and evaluation logic.
    -   `groundingEngine/`: Standard and Semantic grounding engines.
    -   `graphTools/`: Semantic graph management and constraint handling.
-   `data/`: Contains dataset files, configurations, rules, and predictions.
-   `tools/setup/`: Utility scripts and configurations for dataset preparation.

## Configuration

Datasets are configured using JSON files (e.g., `data/hetionet/hetionet.json`). Key fields include:

-   `dataset_name`: Name of the dataset.
-   `train`, `valid`, `test`: Paths to the respective triple files (TSV format).
-   `schema`, `types_file`: Ontology files for semantic constraints.
-   `anyburl_rules`, `amie_rules`: Paths to the rule sets.
-   `predictions_dir`: Where logs and materialized triples will be saved.

## Running Scenarios

### 1. Link Prediction Experiment

Runs evaluation on the test set comparing the **Standard Grounding Engine** vs the **Semantic Grounding Engine**.

```bash
mvn exec:java -Dexec.mainClass="evolveAggregation.RunExperiment" -Dexec.args="path/to/config.json [mode]"
```

-   **Arguments**:
    -   `path/to/config.json`: Path to your dataset JSON (default is `data/hetionet/hetionet.json`).
    -   `mode`: `amie`, `anyburl`, or `full` (runs both).

**Example**:
```bash
mvn exec:java -Dexec.mainClass="evolveAggregation.RunExperiment" -Dexec.args="data/nell995/nell995.json full"
```

### 2. Knowledge Graph Materialization

Applies rules to the whole graph to generate new facts.

```bash
mvn exec:java -Dexec.mainClass="evolveAggregation.RunMaterialization" -Dexec.args="path/to/config.json"
```

-   **Scenario A**: Materialize until the graph grows by a certain percentage (e.g., 10%, 30%).
-   **Scenario B**: Materialize using the top N% of rules by confidence.

The results are saved as `.nt` files in the `predictions/materialization/` directory of your dataset.

### 3. Debug / Single Scenario Materialization

For testing specific materialization settings without running the full suite.

```bash
mvn exec:java -Dexec.mainClass="evolveAggregation.DebugMaterialization" -Dexec.args="[config] [ruleset] [complexity] [target] [mode]"
```

-   **Arguments**:
    -   `config`: Path to config JSON.
    -   `ruleset`: `amie` or `anyburl`.
    -   `complexity`: `FULL` or `CP`.
    -   `target`: Numeric value (e.g., `10.0`).
    -   `mode`: `rules` (Top N% rules) or `triples` (Graph growth %).

## Metrics

The framework evaluates:
-   **Hits@1, Hits@5, Hits@10**: Percentage of correct entities in top K.
-   **MRR**: Mean Reciprocal Rank.
-   **Sem@K**: Semantic consistency score at rank K (percentage of top K predictions that do not violate ontology constraints).
