#!/bin/bash

# --- Configuration ---
# Lines per temporary chunk. Tune to the available memory and input size.
CHUNK_LINES=5000000
# Parallel jobs; defaults to the number of available CPU cores.
MAX_JOBS=$(nproc)
# Scratch directory for the chunks.
TEMP_DIR="triples_chunks_temp"
# ---------------------

# --- Argument validation ---
if [ "$#" -ne 2 ]; then
    echo "Error: invalid number of arguments."
    echo "Usage: $0 <input_file> <output_file>"
    exit 1
fi

INPUT_FILE="$1"
OUTPUT_FILE="$2"

# Fail early if the input does not exist.
if [ ! -f "$INPUT_FILE" ]; then
    echo "Error: input file '$INPUT_FILE' not found."
    exit 1
fi

echo "--- Filtering triples in parallel ---"
echo "Input file:   $INPUT_FILE"
echo "Output file:  $OUTPUT_FILE"
echo "Using up to $MAX_JOBS parallel jobs."

# --- Setup ---
echo "Setting up temporary directory..."
rm -rf "$TEMP_DIR"
rm -f "$OUTPUT_FILE"
mkdir "$TEMP_DIR"

# Starting line count, for the final report.
echo "Calculating initial line count..."
START_SIZE=$(wc -l < "$INPUT_FILE")
echo "Start size of '$INPUT_FILE': $(printf "%'d" $START_SIZE) lines"


# --- Step 1: split the input into chunks ---
echo "Splitting '$INPUT_FILE' into chunks of $(printf "%'d" $CHUNK_LINES) lines each..."
split -l "$CHUNK_LINES" --numeric-suffixes=1 "$INPUT_FILE" "$TEMP_DIR/chunk_"
echo "Splitting complete."


# --- Step 2: filter each chunk in parallel ---
echo "Filtering chunks to remove schema properties and literals..."

PATTERNS_TO_REMOVE='http://www.w3.org/1999/02/22-rdf-syntax-ns#|http://www.w3.org/2000/01/rdf-schema#|http://www.w3.org/2002/07/owl#|http://www.w3.org/ns/shacl#|>[[:space:]]+"'
#PATTERNS_TO_REMOVE='>[[:space:]]+"'
# One background job per chunk.
for chunk in "$TEMP_DIR"/chunk_*; do
    # Each job writes to its own output file, to avoid interleaved writes.
    (grep -vE "$PATTERNS_TO_REMOVE" "$chunk" > "${chunk}.filtered") &

    # Cap concurrency at MAX_JOBS.
    if [[ $(jobs -r -p | wc -l) -ge $MAX_JOBS ]]; then
        wait -n
    fi
done

# Block until the remaining jobs finish.
echo "Waiting for all filtering jobs to complete..."
wait

echo "Filtering complete."

# --- Step 3: combine the filtered chunks ---
echo "Combining filtered chunks..."
cat "$TEMP_DIR"/*.filtered > "$OUTPUT_FILE"

# --- Step 4: clean up ---
echo "Cleaning up temporary chunk files..."
rm -rf "$TEMP_DIR"


# --- Report ---
echo "Calculating final line count..."
END_SIZE=$(wc -l < "$OUTPUT_FILE")
echo "End size of '$OUTPUT_FILE': $(printf "%'d" $END_SIZE) lines"

LINES_REMOVED=$((START_SIZE - END_SIZE))
echo ""
echo "--- Done ---"
echo "Total lines removed: $(printf "%'d" $LINES_REMOVED)"
