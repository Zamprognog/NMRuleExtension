#!/bin/bash
#
# AMIE3 rule mining for one dataset, at both rule lengths used in the paper.
# These are the command lines referenced by the supplementary material.
#
#   ./amiemine.sh                 # defaults to NELL995
#   ./amiemine.sh YAGO4.5 yago4.5 # <data dir under ../data/> <file prefix>
#
# Note -const (pattern rules with constants) is deliberately not used; see the
# paper's experimental setup for why.

set -e
cd "$(dirname "$0")"

DIR="${1:-NELL995}"
PREFIX="${2:-$DIR}"
TRAIN="../data/$DIR/data/${PREFIX}_train.tsv"
OUT="../data/$DIR/rules"

[ -f "$TRAIN" ] || { echo "no such training file: $TRAIN" >&2; exit 1; }
mkdir -p "$OUT"

echo "Starting AMIE+ rule mining on $TRAIN ..."

for MAXAD in 3 4; do
    echo "Running maxad = $MAXAD ..."
    java -jar amie3.5.1.jar -maxad "$MAXAD" -mins 10 -minpca 0.1 -minc 0.1 -nc 16 -full \
        -oute "$TRAIN" | tail -n +16 > "$OUT/${PREFIX}_${MAXAD}CP_rules.tsv"
    echo "  -> $OUT/${PREFIX}_${MAXAD}CP_rules.tsv"
done

echo "All jobs finished successfully!"
