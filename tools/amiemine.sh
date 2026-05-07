#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

echo "Starting AMIE+ rule mining..."

# Run 1: maxad 3
echo "Running first job (maxad = 3)..."
java -jar amie3.5.1.jar -maxad 3 -mins 10 -minpca 0.1 -minc 0.1 -nc 16 -full -oute ../data/OWL2Bench/data/OWL2Bench_train.tsv | tail -n +16 > ../data/OWL2Bench/rules/OWL2Bench_3CP_rules.tsv
echo "First job completed. Output saved to OWL2Bench_3CP_rules.tsv"

# Run 2: maxad 4
echo "Running second job (maxad = 4)..."
java -jar amie3.5.1.jar -maxad 4 -mins 10 -minpca 0.1 -minc 0.1 -nc 16 -full -oute ../data/OWL2Bench/data/OWL2Bench_train.tsv | tail -n +16 > ../data/OWL2Bench/rules/OWL2Bench_4CP_rules.tsv
echo "Second job completed. Output saved to OWL2Bench_4CP_rules.tsv"

echo "All jobs finished successfully!"