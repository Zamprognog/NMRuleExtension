#!/usr/bin/env bash
#
# Reruns the paper experiments (link prediction + materialization + materialization
# evaluation) for NELL995, YAGO4.5-10, CSKG2 and (opt-in) Hetionet.
#
#   ./scripts/run_experiments.sh                          # all stages, the 3 default datasets
#   ./scripts/run_experiments.sh --datasets nell,yago     # subset of datasets
#   ./scripts/run_experiments.sh --stages mat,mateval     # subset of stages
#   ./scripts/run_experiments.sh --dry-run                # print the plan, run nothing
#
# Per-stage stdout is tee'd to runs/<timestamp>/<dataset>_<stage>.log; the Java code
# additionally writes its own logs under each dataset's predictions/ directory.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---------------------------------------------------------------- defaults ----
DATASETS_ALL="nell,yago,cskg"
STAGES_ALL="lp,mat,mateval"

DATASETS="$DATASETS_ALL"
STAGES="$STAGES_ALL"
MODE="full"              # RunExperiment mode: full | anyburl | amie
RULE_PCTS="1,5,10"       # materialization: top-N% rules (paper: 1%, 5%, 10%)
TRIPLE_PCTS=""           # materialization by triple count; empty = scenario disabled
HEAP="12g"
DRY_RUN=0
SKIP_BUILD=0

usage() {
    sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    cat <<EOF

Options:
  --datasets LIST      nell,yago,cskg,hetionet (default: all but hetionet)
  --stages LIST        Comma-separated: lp,mat,mateval (default: all)
  --mode MODE          Rule miners for the LP stage: full|anyburl|amie (default: full)
  --percentages LIST   Top-N% rule thresholds for materialization (default: 1,5,10)
  --triple-pcts LIST   Enable materialization-by-triple-count at these % (default: off)
  --heap SIZE          JVM max heap, e.g. 8g (default: 12g)
  --skip-build         Reuse the existing target/classes and cached classpath
  --dry-run            Print what would run, then exit
  -h, --help           This message
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --datasets)     DATASETS="$2"; shift 2 ;;
        --stages)       STAGES="$2"; shift 2 ;;
        --mode)         MODE="$2"; shift 2 ;;
        --percentages)  RULE_PCTS="$2"; shift 2 ;;
        --triple-pcts)  TRIPLE_PCTS="$2"; shift 2 ;;
        --heap)         HEAP="$2"; shift 2 ;;
        --skip-build)   SKIP_BUILD=1; shift ;;
        --dry-run)      DRY_RUN=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# Dataset key -> config file. Keys are the short names accepted by --datasets.
config_for() {
    case "$1" in
        nell)      echo "data/NELL995/NELL995.json" ;;
        yago)      echo "data/YAGO4.5/yago4.5.json" ;;
        cskg)      echo "data/CSKG2/CSKG2.json" ;;
        hetionet)  echo "data/hetionet/hetionet.json" ;;
        *)         echo "" ;;
    esac
}

# Reads a string value out of the flat JSON config (same shape ExperimentConfig expects).
config_value() {
    local file="$1" key="$2"
    sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -1
}

has_stage() { [[ ",$STAGES," == *",$1,"* ]]; }

RUN_ID="$(date +%Y%m%d_%H%M%S)"
RUN_DIR="runs/$RUN_ID"
CP_FILE="target/experiment-classpath.txt"

# --------------------------------------------------------------- preflight ----
DATASET_LIST=()
IFS=',' read -ra _keys <<< "$DATASETS"
for key in "${_keys[@]}"; do
    key="$(echo "$key" | tr '[:upper:]' '[:lower:]' | xargs)"
    [[ -z "$key" ]] && continue
    cfg="$(config_for "$key")"
    if [[ -z "$cfg" ]]; then
        echo "ERROR: unknown dataset '$key' (known: $DATASETS_ALL,hetionet)" >&2
        exit 2
    fi
    if [[ ! -f "$cfg" ]]; then
        echo "ERROR: config not found for '$key': $cfg" >&2
        exit 2
    fi
    DATASET_LIST+=("$key")
done
[[ ${#DATASET_LIST[@]} -eq 0 ]] && { echo "ERROR: no datasets selected" >&2; exit 2; }

for st in $(echo "$STAGES" | tr ',' ' '); do
    [[ ",$STAGES_ALL," == *",$st,"* ]] || { echo "ERROR: unknown stage '$st'" >&2; exit 2; }
done

echo "=============================================================="
echo " Run ID     : $RUN_ID"
echo " Datasets   : ${DATASET_LIST[*]}"
echo " Stages     : $STAGES"
echo " LP mode    : $MODE"
echo " Rule pcts  : $RULE_PCTS      Triple pcts: ${TRIPLE_PCTS:-<disabled>}"
echo " Heap       : $HEAP"
echo " Logs       : $RUN_DIR/"
echo "=============================================================="

# Warn early about inputs the Java side would only discover mid-run.
echo
echo "--- Preflight: input files ---"
PREFLIGHT_FATAL=0
for key in "${DATASET_LIST[@]}"; do
    cfg="$(config_for "$key")"
    echo "[$key] $cfg"
    for k in train valid test schema types_file; do
        path="$(config_value "$cfg" "$k")"
        if [[ -z "$path" ]]; then
            echo "    ! $k: not set in config"
        elif [[ ! -f "$path" ]]; then
            echo "    ! $k: MISSING -> $path"
            [[ "$k" == "train" || "$k" == "test" ]] && PREFLIGHT_FATAL=1
        fi
    done
    # The full graph is only needed by the materialization-evaluation stage.
    graph="$(config_value "$cfg" "graph")"
    if has_stage mateval && [[ -n "$graph" && ! -f "$graph" ]]; then
        echo "    ! graph: MISSING -> $graph (mateval needs it)"
    fi
    for k in anyburl_rules anyburl_rules_CP amie_rules amie_rules_CP; do
        path="$(config_value "$cfg" "$k")"
        if [[ -z "$path" ]]; then
            echo "    - $k: not configured, will be skipped"
        elif [[ ! -f "$path" ]]; then
            echo "    - $k: MISSING -> $path (will be skipped)"
        elif [[ ! -s "$path" ]]; then
            echo "    - $k: EMPTY -> $path (will be skipped)"
        fi
    done
done
if [[ $PREFLIGHT_FATAL -eq 1 ]]; then
    echo
    echo "ERROR: required train/test splits are missing. Download the datasets (see README) first." >&2
    exit 1
fi

if [[ $DRY_RUN -eq 1 ]]; then
    echo
    echo "Dry run: planned invocations"
    for key in "${DATASET_LIST[@]}"; do
        cfg="$(config_for "$key")"
        has_stage lp      && echo "  java ... RunExperiment $cfg $MODE"
        has_stage mat     && echo "  java ... RunMaterialization $cfg $RULE_PCTS $TRIPLE_PCTS"
        has_stage mateval && echo "  java ... MaterializationEvaluationPipeline $cfg <latest materialization dir>"
    done
    exit 0
fi

mkdir -p "$RUN_DIR"

# ------------------------------------------------------------------ build ----
if [[ $SKIP_BUILD -eq 0 ]]; then
    echo
    echo "--- Building (mvn clean compile) ---"
    mvn -q -DskipTests clean compile 2>&1 | tee "$RUN_DIR/build.log"
    [[ ${PIPESTATUS[0]} -eq 0 ]] || { echo "ERROR: build failed, see $RUN_DIR/build.log" >&2; exit 1; }
    echo "--- Resolving classpath ---"
    mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >> "$RUN_DIR/build.log" 2>&1 \
        || { echo "ERROR: classpath resolution failed, see $RUN_DIR/build.log" >&2; exit 1; }
fi
[[ -f "$CP_FILE" ]] || { echo "ERROR: $CP_FILE missing; rerun without --skip-build" >&2; exit 1; }
CLASSPATH="target/classes:$(cat "$CP_FILE")"

JAVA_OPTS=(-Xmx"$HEAP" -XX:+UseG1GC -Dfile.encoding=UTF-8)

# ------------------------------------------------------------------- runs ----
SUMMARY=()

run_stage() {
    local dataset="$1" stage="$2" main_class="$3"; shift 3
    local log="$RUN_DIR/${dataset}_${stage}.log"
    local start end status
    echo
    echo "=============================================================="
    echo ">>> [$dataset] stage=$stage  ($(date '+%H:%M:%S'))"
    echo ">>> $main_class $*"
    echo "=============================================================="
    start=$(date +%s)
    java "${JAVA_OPTS[@]}" -cp "$CLASSPATH" "$main_class" "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    end=$(date +%s)
    local mins=$(( (end - start) / 60 )) secs=$(( (end - start) % 60 ))
    if [[ $status -eq 0 ]]; then
        SUMMARY+=("OK    $dataset/$stage  ${mins}m${secs}s  $log")
    else
        SUMMARY+=("FAIL  $dataset/$stage  ${mins}m${secs}s  $log (exit $status)")
        echo "!!! [$dataset] stage=$stage FAILED (exit $status) — continuing" >&2
    fi
}

# Newest timestamped subdirectory of <predictions_dir>/materialization/, if any.
latest_materialization_dir() {
    local cfg="$1"
    local base
    base="$(config_value "$cfg" "predictions_dir")materialization"
    [[ -d "$base" ]] || return 1
    ls -1d "$base"/*/ 2>/dev/null | sort | tail -1
}

for key in "${DATASET_LIST[@]}"; do
    cfg="$(config_for "$key")"

    if has_stage lp; then
        run_stage "$key" lp nmRuleExtension.RunExperiment "$cfg" "$MODE"
    fi

    if has_stage mat; then
        run_stage "$key" mat nmRuleExtension.RunMaterialization "$cfg" "$RULE_PCTS" "$TRIPLE_PCTS"
    fi

    if has_stage mateval; then
        mat_dir="$(latest_materialization_dir "$cfg" || true)"
        if [[ -n "$mat_dir" ]]; then
            run_stage "$key" mateval nmRuleExtension.evaluation.MaterializationEvaluationPipeline "$cfg" "$mat_dir"
        else
            echo "!!! [$key] no materialization output found; run the 'mat' stage first" >&2
            SUMMARY+=("SKIP  $key/mateval  no materialization output")
        fi
    fi
done

# ---------------------------------------------------------------- summary ----
echo
echo "=============================================================="
echo " Summary (run $RUN_ID)"
echo "=============================================================="
printf '%s\n' "${SUMMARY[@]}" | tee "$RUN_DIR/summary.txt"

# Extract the metric tables out of the raw logs into CSV + markdown.
echo
"$REPO_ROOT/scripts/collect_results.sh" "$RUN_DIR" || echo "WARNING: result collection failed" >&2

grep -q '^FAIL' "$RUN_DIR/summary.txt" && exit 1
exit 0
