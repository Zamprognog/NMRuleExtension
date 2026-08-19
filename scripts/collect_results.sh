#!/usr/bin/env bash
#
# Extracts structured results from a run directory's raw stage logs.
#
#   ./scripts/collect_results.sh [runs/<timestamp>]     # defaults to the newest run
#
# Writes, into that same directory:
#   lp_results.csv   dataset,ruleset,engine,hits1,hits5,hits10,mrr,sem10,sem100
#   mat_results.csv  dataset,ruleset,engine,pct,rules_available,rules_target,
#                    rules_applied,triples,elapsed_ms
#   RESULTS.md       both of the above as readable tables
#
# Safe to re-run: it only reads *_lp.log / *_mat.log and rewrites its own outputs.

set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RUN_DIR="${1:-$(ls -1dt runs/*/ 2>/dev/null | head -1)}"
RUN_DIR="${RUN_DIR%/}"
[[ -d "$RUN_DIR" ]] || { echo "ERROR: run directory not found: ${RUN_DIR:-<none>}" >&2; exit 1; }

LP_CSV="$RUN_DIR/lp_results.csv"
MAT_CSV="$RUN_DIR/mat_results.csv"
MD="$RUN_DIR/RESULTS.md"

# ------------------------------------------------------------ LP metrics -----
echo "dataset,ruleset,engine,hits1,hits5,hits10,mrr,sem10,sem100" > "$LP_CSV"
for log in "$RUN_DIR"/*_lp.log; do
    [[ -e "$log" ]] || continue
    dataset="$(basename "$log" _lp.log)"
    awk -v ds="$dataset" '
        # "              EVALUATING ANYBURL RULES (ALL)      "
        /EVALUATING .* RULES/ {
            miner = /ANYBURL/ ? "AnyBURL" : "AMIE"
            variant = $0
            sub(/.*\(/, "", variant); sub(/\).*/, "", variant)
            gsub(/[ \t]/, "", variant)
            ruleset = miner "_" variant
            next
        }
        # "Standard        | 0.3162     | ... | 0.9907    "
        /^(Standard|Semantic)[ \t]*\|/ {
            engine = $1
            n = split($0, f, /[ \t]*\|[ \t]*/)
            for (i = 2; i <= n; i++) gsub(/[ \t]+$/, "", f[i])
            # f[1]=engine, f[2..7]=hits1,hits5,hits10,mrr,sem10,sem100
            if (n >= 7 && ruleset != "")
                printf "%s,%s,%s,%s,%s,%s,%s,%s,%s\n", ds, ruleset, engine, f[2], f[3], f[4], f[5], f[6], f[7]
        }
    ' "$log" >> "$LP_CSV"
done

# ------------------------------------------- materialization per condition ---
echo "dataset,ruleset,engine,pct,rules_available,rules_target,rules_applied,triples,elapsed_ms" > "$MAT_CSV"
for log in "$RUN_DIR"/*_mat.log; do
    [[ -e "$log" ]] || continue
    dataset="$(basename "$log" _mat.log)"
    awk -v ds="$dataset" '
        /^--- Ruleset: /            { ruleset = $3 "_" $4; next }
        /^--- Condition: /          { engine = $3; pct = $7; sub(/%/, "", pct); next }
        # "Total rules available: 66845. Using top 5.0% (3343 rules)."
        /^Total rules available:/   { avail = $4; sub(/\./, "", avail); target = $8; sub(/\(/, "", target); next }
        /Rules that added new triples:/ { applied = $NF; next }
        /^Total elapsed time for materialization:/ { elapsed = $6; next }
        # "Successfully wrote 4705 triples to <path>"
        /^Successfully wrote /      {
            triples = $3
            printf "%s,%s,%s,%s,%s,%s,%s,%s,%s\n", ds, ruleset, engine, pct, avail, target, applied, triples, elapsed
        }
    ' "$log" >> "$MAT_CSV"
done

# ------------------------------------------------------------- markdown ------
{
    echo "# Experiment results — $(basename "$RUN_DIR")"
    echo
    echo "Generated $(date '+%Y-%m-%d %H:%M'). Source logs: \`$RUN_DIR/\`."
    echo
    if [[ -s "$RUN_DIR/summary.txt" ]]; then
        echo "## Stage status"
        echo
        echo '```'
        cat "$RUN_DIR/summary.txt"
        echo '```'
        echo
    fi

    echo "## Link prediction"
    echo
    echo "| Dataset | Rule set | Engine | Hits@1 | Hits@5 | Hits@10 | MRR | Sem@10 | Sem@100 |"
    echo "|---|---|---|---|---|---|---|---|---|"
    tail -n +2 "$LP_CSV" | awk -F, '{printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", $1,$2,$3,$4,$5,$6,$7,$8,$9}'
    echo

    echo "## Materialization"
    echo
    echo "| Dataset | Rule set | Engine | % rules | Available | Target | Applied | Triples | Time (ms) |"
    echo "|---|---|---|---|---|---|---|---|---|"
    tail -n +2 "$MAT_CSV" | awk -F, '{printf "| %s | %s | %s | %s%% | %s | %s | %s | %s | %s |\n", $1,$2,$3,$4,$5,$6,$7,$8,$9}'
} > "$MD"

echo "Wrote:"
printf '  %s (%d rows)\n' "$LP_CSV"  "$(( $(wc -l < "$LP_CSV") - 1 ))"
printf '  %s (%d rows)\n' "$MAT_CSV" "$(( $(wc -l < "$MAT_CSV") - 1 ))"
printf '  %s\n' "$MD"
