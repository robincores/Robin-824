#!/bin/bash
set -euo pipefail

# Determine the directory where this script is located
SCRIPT_DIR="$(dirname "$(realpath "$0")")"

# Path to the JAR file based on the script's location
JAR_FILE="$SCRIPT_DIR/../target/skyline-jar-with-dependencies.jar"

# Check if the JAR file exists
if [ ! -f "$JAR_FILE" ]; then
  echo "Error: skyline-jar-with-dependencies.jar not found in $SCRIPT_DIR/../target."
  echo "Please build the project with Maven or ensure the JAR file is in the correct location."
  exit 1
fi

# If no arguments, show a basic hint (optional)
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 [args...]"
  echo "Example: $0 --tokens hello.c"
  exit 1
fi

# Pass-through to Java
java -cp "$JAR_FILE" io.github.robincores.toolchain.r8cc.cli.Main "$@"
