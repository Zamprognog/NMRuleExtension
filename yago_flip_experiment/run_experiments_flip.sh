#!/bin/bash
# LP + materialization + violation counting for YAGO4.5-10-flip.
#
# Mirrors scripts/run_experiments.sh: direct `java -Xmx.. -cp target/classes:<deps>`
# (not `mvn exec:java`), same JAVA_OPTS, per-stage logs in a timestamped dir.
#
#   ./run_experiments_flip.sh [lp|mat|mateval|all]     default: all
#
# Rule sets come from YAGO4.5-10-flip.json; unset paths are skipped by the Java
# side. Currently: anyburl ALL-300, anyburl CP-300, amie 3CP.
# (amie_rules is intentionally empty -- the maxad-4 run was abandoned.)

set -uo pipefail
cd "$(dirname "$0")"
REPO=".."
CONFIG="yago_flip_experiment/YAGO4.5-10-flip.json"

HEAP="${HEAP:-12g}"          # matches run_experiments.sh default; proven on this box
STAGES="${1:-all}"
TS=$(date +%Y%m%d_%H%M%S)
RUN_DIR="runs/$TS"
mkdir -p "$RUN_DIR" predictions

CP_FILE="$REPO/inverse_functionality_experiment/.cp.txt"
[ -s "$CP_FILE" ] || { echo "missing classpath file $CP_FILE" >&2; exit 1; }
# NB: target/classes is relative to the REPO ROOT, because stage() does
# `cd "$REPO"` before invoking java. Using "$REPO/target/classes" here
# resolves outside the repo once we have cd'd, and java then cannot find
# the main class. Dependency paths from .cp.txt are absolute, so fine.
CLASSPATH="target/classes:$(cat "$CP_FILE")"
JAVA_OPTS=(-Xmx"$HEAP" -XX:+UseG1GC -Dfile.encoding=UTF-8)

echo "run dir : $RUN_DIR"
echo "heap    : $HEAP"
echo "config  : $CONFIG"

stage() {
  local name="$1" main="$2"; shift 2
  local log="$RUN_DIR/${name}.log"
  echo
  echo "=============================================================="
  echo ">>> stage=$name  $(date '+%H:%M:%S')"
  echo ">>> $main $*"
  echo "=============================================================="
  local start=$(date +%s)
  ( cd "$REPO" && java "${JAVA_OPTS[@]}" -cp "$CLASSPATH" "$main" "$@" ) 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  local end=$(date +%s) mins secs
  mins=$(( (end-start)/60 )); secs=$(( (end-start)%60 ))
  if [ $status -eq 0 ]; then echo "OK   $name  ${mins}m${secs}s"
  else echo "FAIL $name  ${mins}m${secs}s (exit $status) -- continuing" >&2; fi
  echo "$name ${mins}m${secs}s exit=$status" >> "$RUN_DIR/summary.txt"
}

latest_mat_dir() {
  ls -1dt predictions/materialization/*/ 2>/dev/null | head -1
}

# NB: plain conditionals, not `case ... ;;&` -- macOS ships bash 3.2, which
# does not support fallthrough.
run_lp=0; run_mat=0; run_mateval=0
case "$STAGES" in
  lp)      run_lp=1 ;;
  mat)     run_mat=1 ;;
  mateval) run_mateval=1 ;;
  all)     run_lp=1; run_mat=1; run_mateval=1 ;;
  *) echo "unknown stage: $STAGES (use lp|mat|mateval|all)" >&2; exit 1 ;;
esac

[ $run_lp -eq 1 ]  && stage lp  nmRuleExtension.RunExperiment "$CONFIG" full
[ $run_mat -eq 1 ] && stage mat nmRuleExtension.RunMaterialization "$CONFIG" 1,5,10

if [ $run_mateval -eq 1 ]; then
  d=$(latest_mat_dir)
  if [ -n "$d" ]; then
    stage mateval nmRuleExtension.evaluation.MaterializationEvaluationPipeline \
          "$CONFIG" "yago_flip_experiment/${d%/}/"
  else
    echo "no materialization output found; skipping mateval" >&2
  fi
fi

echo; echo "=== summary ==="; cat "$RUN_DIR/summary.txt" 2>/dev/null
