#!/bin/bash

# Determine the directory where this script is located
SCRIPT_DIR=$(dirname "$(realpath "$0")")

# Path to the JAR file based on the script's location
JAR_FILE="$SCRIPT_DIR/../target/skyline-jar-with-dependencies.jar"

# Check if the JAR file exists
if [ ! -f "$JAR_FILE" ]; then
  echo "Error: skyline-jar-with-dependencies.jar not found in $SCRIPT_DIR/../target."
  echo "Please build the project with Maven or ensure the JAR file is in the correct location."
  exit 1
fi

# Check if at least one argument (the source file) is provided
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <source.asm> [<output.bin>]"
  echo "Example: $0 myprogram.asm myprogram.bin"
  exit 1
fi

# Get the source file from the first argument
SOURCE_FILE=$1

# If the second argument (output file) is not provided, generate one based on the source file
if [ "$#" -lt 2 ]; then
  OUTPUT_FILE="$(basename "$SOURCE_FILE" .asm).bin"
else
  OUTPUT_FILE=$2
fi

# Invoke the assembler with the source and output files
java -cp "$JAR_FILE" org.robincores.r8.assembler.Main "$SOURCE_FILE" "$OUTPUT_FILE"
