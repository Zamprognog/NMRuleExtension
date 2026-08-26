#!/bin/bash
# Overnight violation counting for YAGO4.5-10-flip materialization.
#
# Two things this script exists to get right:
#
# 1. SLEEP. `pmset -g` reports `sleep 1` on AC -- this machine suspends after one
#    minute idle, held off only by transient assertions (powerd while the display
#    is on, and Claude Code's own `caffeinate -i -t 300`, which expires after 5
#    minutes). That is the most likely reason earlier unattended runs died at
#    inconsistent times with no OOM and no Java error. `caffeinate -dimsu` here
#    holds the assertion for exactly as long as the JVM runs, and releases it
#    afterwards -- no permanent change to the user's power settings.
#
# 2. DETACHMENT. nohup + setsid so the job outlives the terminal and the agent
#    session that started it.
#
# The pipeline already loads the base graph ONCE (Dataset built before the file
# loop) and cycles each materialized file through a named graph
# (addNamedModel -> queries -> removeNamedModel), so passing the DIRECTORY is
# strictly cheaper than 18 single-file invocations.

set -uo pipefail
cd "$(dirname "$0")"
REPO=".."

MAT_DIR="${MAT_DIR:-predictions/materialization/20260824_221804}"
HEAP="${HEAP:-12g}"
CONFIG="yago_flip_experiment/YAGO4.5-10-flip.json"
TS=$(date +%Y%m%d_%H%M%S)
LOG="runs/mateval_overnight_$TS.log"

mkdir -p runs
[ -d "$MAT_DIR" ] || { echo "materialization dir not found: $MAT_DIR" >&2; exit 1; }
NFILES=$(ls -1 "$MAT_DIR"/*.nt 2>/dev/null | wc -l | tr -d ' ')
[ "$NFILES" -gt 0 ] || { echo "no .nt files in $MAT_DIR" >&2; exit 1; }

CP_FILE="$REPO/inverse_functionality_experiment/.cp.txt"
[ -s "$CP_FILE" ] || { echo "missing classpath file $CP_FILE" >&2; exit 1; }

echo "files to evaluate : $NFILES"
echo "materialization   : $MAT_DIR"
echo "heap              : $HEAP"
echo "log               : $LOG"
echo "started           : $(date)"

# caffeinate wraps java: -d display, -i idle, -m disk, -s system, -u user-active.
# It exits when java exits, so the assertion is scoped to the job.
cd "$REPO"
caffeinate -dimsu java -Xmx"$HEAP" -XX:+UseG1GC -Dfile.encoding=UTF-8 \
    -cp "target/classes:$(cat inverse_functionality_experiment/.cp.txt)" \
    nmRuleExtension.evaluation.MaterializationEvaluationPipeline \
    "$CONFIG" "yago_flip_experiment/$MAT_DIR/" \
  > "yago_flip_experiment/$LOG" 2>&1

status=$?
echo "finished          : $(date)  exit=$status"
echo "files completed   : $(grep -c '^Evaluating: ' "yago_flip_experiment/$LOG" 2>/dev/null)"
