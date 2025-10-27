package foo

import org.apache.spark.sql.{DataFrame, SparkSession}

object Foo {

  /**
   * Creates a simple DataFrame with a single "Hello, World!" message.
   *
   * This demonstrates the basic pattern of creating DataFrames from Scala collections.
   * In production, you'd typically read from external sources (CSV, Parquet, databases),
   * but this shows the fundamental transformation: Scala data → Spark DataFrame.
   *
   * @param spark The SparkSession (entry point to Spark functionality)
   * @return A DataFrame with one column named "message" containing one row
   */
  def helloWorld(spark: SparkSession): DataFrame = {
    // Start with a simple Scala collection
    val data = Seq("Hello, World!")

    // Convert to DataFrame:
    // 1. data.map(Tuple1(_)) wraps each string in a single-element tuple
    // 2. spark.createDataFrame() converts Scala collection to distributed DataFrame
    // 3. toDF("message") names the column (default would be "_1")
    val df = spark.createDataFrame(data.map(Tuple1(_))).toDF("message")

    df
  }

  def main(args: Array[String]): Unit = {
    // Initialize SparkSession - the entry point to all Spark functionality
    // This is required before any Spark operations can be performed
    val spark = SparkSession.builder()
      .appName("HelloWorld") // Names your application (shows in Spark UI)
      .master("local[*]") // Run locally using all CPU cores
      .getOrCreate() // Reuse existing session or create new one

    // Create and display the DataFrame
    // .show() is an "action" that triggers execution and prints results
    helloWorld(spark).show()

    // Always stop the SparkSession when done
    // Releases cluster resources and cleans up temporary files
    // Critical in production; prevents resource leaks in tests
    spark.stop()
  }
}
