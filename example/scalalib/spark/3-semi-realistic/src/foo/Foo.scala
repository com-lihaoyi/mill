package foo

import org.apache.spark.sql.{SparkSession, Dataset, DataFrame}
import org.apache.spark.sql.functions._

/**
 * Semi-realistic Spark application demonstrating common analytics patterns.
 *
 * This example shows the fundamental workflow for business intelligence and reporting:
 * 1. Read structured data (CSV with schema)
 * 2. Transform to typed Dataset for safety
 * 3. Aggregate by dimension (category) with multiple metrics
 * 4. Display results
 *
 * This pattern scales from laptop development to petabyte-scale production clusters.
 */
object Foo {

  /**
   * Case class representing a transaction record.
   *
   * Mapping DataFrames to case classes provides:
   * - Compile-time type safety (catch errors before runtime)
   * - IDE autocomplete for column names
   * - Encoder generation for efficient serialization
   *
   * This is the recommended pattern for production Spark applications.
   */
  case class Transaction(id: Int, category: String, amount: Double)

  /**
   * Compute summary statistics by category using GROUP BY + aggregation.
   *
   * This demonstrates the most common Spark pattern for analytics:
   * - groupBy: Partition data by one or more dimensions (like SQL GROUP BY)
   * - agg: Apply multiple aggregation functions to each group
   *
   * **How it scales:**
   * - Spark partitions data across cluster nodes
   * - Each partition computes partial aggregates in parallel
   * - Final aggregation combines results from all partitions
   *
   * **Real-world examples:**
   * - Revenue by region: groupBy("region").agg(sum("revenue"))
   * - Active users by month: groupBy("month").agg(countDistinct("user_id"))
   * - Sensor metrics: groupBy("device_id", "hour").agg(avg("temperature"), max("temperature"))
   *
   * @param transactions Typed dataset of transaction records
   * @return DataFrame with summary statistics (category, total, average, count)
   */
  def computeSummary(transactions: Dataset[Transaction]): DataFrame = {
    transactions
      .groupBy("category") // Partition by category (creates one group per unique category)
      .agg(
        sum("amount").alias("total_amount"), // Sum of all amounts in this category
        avg("amount").alias("average_amount"), // Mean amount per transaction
        count("amount").alias("transaction_count") // Number of transactions in category
      )
    // Note: All three aggregations execute in a single pass over the data (efficient!)
  }

  /**
   * Application entry point demonstrating a realistic analytics workflow.
   *
   * This follows the standard Spark application lifecycle:
   * 1. Initialize SparkSession
   * 2. Read data from source (CSV file)
   * 3. Transform to typed Dataset
   * 4. Perform aggregations
   * 5. Display/write results
   * 6. Clean up resources
   */
  def main(args: Array[String]): Unit = {
    // Initialize SparkSession - the entry point to Spark functionality
    val spark = SparkSession.builder()
      .appName("SparkExample") // Name appears in Spark UI and logs
      .master("local[*]") // Run locally using all CPU cores
      .getOrCreate() // Reuse existing session if available

    // Flexible file path handling: command-line arg → resources → error
    // Development: Use resources/transactions.csv
    // Production: Pass HDFS/S3 path as argument
    val resourcePath: String = args.headOption
      .orElse(Option(getClass.getResource("/transactions.csv")).map(_.getPath))
      .getOrElse(throw new RuntimeException(
        "transactions.csv not provided as argument and not found in resources"
      ))

    // Import spark.implicits for Dataset conversions (like .as[Transaction])
    // Must be after SparkSession initialization
    import spark.implicits._

    // Read CSV with schema inference
    // Production tip: Replace inferSchema with explicit schema for better performance
    val df = spark.read
      .option("header", "true") // First row contains column names
      .option("inferSchema", "true") // Auto-detect types (Int, Double, String)
      .csv(resourcePath)

    // Convert untyped DataFrame to typed Dataset[Transaction]
    // This gives compile-time type safety and better IDE support
    // Fails at runtime if CSV columns don't match case class structure
    val transactionsDS: Dataset[Transaction] = df.as[Transaction]

    // Compute summary statistics using our aggregation function
    val summaryDF = computeSummary(transactionsDS)

    // Display results to console
    // .show() is an ACTION that triggers Spark to execute the query plan
    println("Summary Statistics by Category:")
    summaryDF.show()

    // Always stop SparkSession to release resources
    // Critical in production to avoid resource leaks
    spark.stop()
  }
}
