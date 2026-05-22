#!/bin/bash

# --- Configuration ---
# Number of lines per temporary chunk file.
# Adjust based on your RAM and file size. 5 million is a reasonable start.
CHUNK_LINES=5000000
# Number of parallel jobs to run. Defaults to the number of available CPU cores.
MAX_JOBS=$(nproc)
# A temporary directory to store the file chunks.
TEMP_DIR="triples_chunks_temp"
# ---------------------

# --- Argument Validation ---
# Check if exactly two arguments (input and output file) were provided.
if [ "$#" -ne 2 ]; then
    echo "❌ Error: Invalid number of arguments."
    echo "Usage: $0 <input_file> <output_file>"
    exit 1
fi

INPUT_FILE="$1"
OUTPUT_FILE="$2"

# Check if the input file actually exists before we start.
if [ ! -f "$INPUT_FILE" ]; then
    echo "❌ Error: Input file '$INPUT_FILE' not found."
    exit 1
fi

echo "--- Starting triple filtering process in parallel 🚀 ---"
echo "Input file:   $INPUT_FILE"
echo "Output file:  $OUTPUT_FILE"
echo "Using up to $MAX_JOBS parallel jobs."

# --- Setup: Create a clean environment ---
echo "Setting up temporary directory..."
rm -rf "$TEMP_DIR"
rm -f "$OUTPUT_FILE"
mkdir "$TEMP_DIR"

# Get the starting line count for comparison.
echo "Calculating initial line count..."
START_SIZE=$(wc -l < "$INPUT_FILE")
echo "Start size of '$INPUT_FILE': $(printf "%'d" $START_SIZE) lines"


# --- Step 1: Split the large file into manageable chunks ---
echo "Splitting '$INPUT_FILE' into chunks of $(printf "%'d" $CHUNK_LINES) lines each..."
split -l "$CHUNK_LINES" --numeric-suffixes=1 "$INPUT_FILE" "$TEMP_DIR/chunk_"
echo "Splitting complete."


# --- Step 2: Process each chunk in parallel ---
echo "Filtering chunks to remove schema properties and all literals... this may take a while."

PATTERNS_TO_REMOVE='http://www.w3.org/1999/02/22-rdf-syntax-ns#|http://www.w3.org/2000/01/rdf-schema#|http://www.w3.org/2002/07/owl#|http://www.w3.org/ns/shacl#|>[[:space:]]+"'
#PATTERNS_TO_REMOVE='>[[:space:]]+"'
# Loop through each chunk file created by the split command.
for chunk in "$TEMP_DIR"/chunk_*; do
    # Each parallel job now writes to its OWN unique temporary output file
    # to prevent race conditions.
    (grep -vE "$PATTERNS_TO_REMOVE" "$chunk" > "${chunk}.filtered") &

    # Simple job management.
    if [[ $(jobs -r -p | wc -l) -ge $MAX_JOBS ]]; then
        wait -n
    fi
done

# 'wait' ensures that all remaining background jobs have finished.
echo "Waiting for all filtering jobs to complete..."
wait

echo "Filtering complete. All chunks have been processed."

# --- Step 3: Combine filtered chunks into the final output file ---
echo "Combining filtered chunks..."
cat "$TEMP_DIR"/*.filtered > "$OUTPUT_FILE"

# --- Step 4: Clean up the temporary files ---
echo "Cleaning up temporary chunk files..."
rm -rf "$TEMP_DIR"


# --- Final Report ---
echo "Calculating final line count..."
END_SIZE=$(wc -l < "$OUTPUT_FILE")
echo "End size of '$OUTPUT_FILE': $(printf "%'d" $END_SIZE) lines"

LINES_REMOVED=$((START_SIZE - END_SIZE))
echo ""
echo "--- ✅ Process Finished ---"
echo "Total lines removed: $(printf "%'d" $LINES_REMOVED)"
