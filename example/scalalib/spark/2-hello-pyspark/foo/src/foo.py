from pyspark.sql import SparkSession, DataFrame

def hello_world(spark: SparkSession) -> DataFrame:
    """
    Creates a simple DataFrame with a single "Hello, World!" message.

    This demonstrates the basic pattern of creating DataFrames from Python data structures.
    In production, you'd typically read from external sources (CSV, Parquet, databases),
    but this shows the fundamental transformation: Python data → Spark DataFrame.

    Args:
        spark: Active SparkSession (the entry point to Spark functionality)

    Returns:
        DataFrame with a single 'message' column containing "Hello, World!"

    Note:
        The tuple syntax [(value,)] is required because createDataFrame expects
        a list of tuples, and (value,) creates a single-element tuple in Python.
    """
    # Create Python data - in production, this would be spark.read.csv() or similar
    data = [("Hello, World!",)]

    # Convert to Spark DataFrame with schema ["message"]
    # This operation is lazy - no computation happens yet
    df = spark.createDataFrame(data, ["message"])

    return df

def main():
    """
    Application entry point demonstrating the standard Spark application lifecycle.

    This pattern (initialize → transform → action → cleanup) is the foundation
    of every Spark application, from simple scripts to complex production pipelines.
    """
    # Initialize SparkSession - the entry point to all Spark functionality
    # .builder uses the builder pattern (common in Java/Scala, also works in Python)
    spark = SparkSession.builder \
        .appName("HelloWorld") \
        .master("local[*]") \
        .getOrCreate()
    # appName: Identifies this app in Spark UI and logs
    # master("local[*]"): Run locally using all CPU cores
    # getOrCreate(): Reuse existing session if available (important for testing)

    # Call our transformation function and trigger computation with .show()
    # .show() is an ACTION that forces Spark to execute the DataFrame operations
    hello_world(spark).show()

    # Always stop SparkSession to release resources
    # Critical in production to avoid resource leaks
    spark.stop()

if __name__ == "__main__":
    # Standard Python idiom: only run main() when executed as a script
    # (not when imported as a module for testing)
    main()