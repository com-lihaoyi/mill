#!/bin/bash

# Check if at least 2 arguments are provided
if [ "$#" -lt 2 ]; then
  echo "Usage: $0 path/to/your-module-assembly.jar fully.qualified.MainClass [path/to/resource.csv]"
  exit 1
fi

# The first argument is the JAR path, the second is the main class
JAR_PATH="$1"
MAIN_CLASS="$2"
MASTER="local[*]"

# Shift out the first two arguments so that any remaining ones (like a resource argument) are forwarded
shift 2

# Function to install Apache Spark via Homebrew (macOS)
install_spark_brew() {
  echo "Installing Apache Spark via Homebrew..."
  brew update && brew install apache-spark
}

# Function to download and extract Apache Spark manually
install_spark_manual() {
  SPARK_VERSION="3.3.0"
  HADOOP_VERSION="3"
  SPARK_PACKAGE="spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION}"
  DOWNLOAD_URL="https://dlcdn.apache.org/spark/spark-${SPARK_VERSION}/${SPARK_PACKAGE}.tgz"
  INSTALL_DIR="$HOME/spark"

  mkdir -p "$INSTALL_DIR"
  echo "Downloading Apache Spark from $DOWNLOAD_URL..."
  # Use -fL to fail on HTTP errors and follow redirects.
  curl -fL "$DOWNLOAD_URL" -o "$INSTALL_DIR/${SPARK_PACKAGE}.tgz" || { echo "Download failed."; exit 1; }

  echo "Extracting Apache Spark..."
  tar -xzf "$INSTALL_DIR/${SPARK_PACKAGE}.tgz" -C "$INSTALL_DIR" || { echo "Extraction failed."; exit 1; }

  # Set SPARK_HOME and update PATH
  export SPARK_HOME="$INSTALL_DIR/${SPARK_PACKAGE}"
  export PATH="$SPARK_HOME/bin:$PATH"
}

# Check if spark-submit is installed
if ! command -v spark-submit &> /dev/null; then
  echo "spark-submit not found. Installing Apache Spark..."
  if command -v brew &> /dev/null; then
    install_spark_brew
  else
    install_spark_manual
  fi
fi

# Verify installation
if ! command -v spark-submit &> /dev/null; then
  echo "spark-submit is still not available. Exiting."
  exit 1
fi

echo "spark-submit is installed. Running the Spark application..."

# Run spark-submit, forwarding any additional arguments (e.g., resource path) to the application
spark-submit \
  --class "$MAIN_CLASS" \
  --master "$MASTER" \
  "$JAR_PATH" \
  "$@"