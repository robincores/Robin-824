#!/bin/bash

# Determine the directory where this script is located
SCRIPT_DIR=$(dirname "$(realpath "$0")")

# Path to the JAR file based on the script's location
JAR_FILE="$SCRIPT_DIR/../target/skyline-mac-aarch64.jar"

# Check if the JAR file exists
if [ ! -f "$JAR_FILE" ]; then
  echo "Error: skyline-mac-aarch64.jar not found in $SCRIPT_DIR/../target."
  echo "Please build the project with Maven or ensure the JAR file is in the correct location."
  exit 1
fi

RELAX=0
RELAX_MAX=""

# Parse optional flags
while [[ "$1" == --* ]]; do
  case "$1" in
    --relax|--relax-branches)
      RELAX=1
      shift
      ;;
    --relax-max)
      shift
      RELAX_MAX="$1"
      shift
      ;;
    --strict)
      RELAX=0
      shift
      ;;
    --)
      shift
      break
      ;;
    *)
      break
      ;;
  esac
done

# Check if at least one argument (the source file) is provided
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 [--relax|--relax-branches] [--relax-max N] <source.asm> [<output.bin>]"
  echo "Example: $0 --relax ROBIN-16/BASIC/basic.asm basic.bin"
  exit 1
fi

SOURCE_FILE=$1

# If the second argument (output file) is not provided, generate one based on the source file
if [ "$#" -lt 2 ]; then
  OUTPUT_FILE="$(basename "$SOURCE_FILE" .asm).bin"
else
  OUTPUT_FILE=$2
fi

JAVA_OPTS=""
if [ "$RELAX" -eq 1 ]; then
  JAVA_OPTS="$JAVA_OPTS -Dr8as.relax_branches=true"
fi
if [ -n "$RELAX_MAX" ]; then
  JAVA_OPTS="$JAVA_OPTS -Dr8as.relax_max_passes=$RELAX_MAX"
fi

# Invoke the assembler with the source and output files
java $JAVA_OPTS -cp "$JAR_FILE" io.github.robincores.toolchain.r8as.Main "$SOURCE_FILE" "$OUTPUT_FILE"
