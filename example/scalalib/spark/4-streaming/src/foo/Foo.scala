package foo

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.streaming.{StreamingQuery, Trigger}
import org.apache.spark.sql.functions._

/**
 * Spark Structured Streaming example demonstrating file-based streaming.
 *
 * This shows the fundamental streaming pattern:
 * 1. Read stream from source (files, Kafka, sockets)
 * 2. Transform data (same DataFrame API as batch)
 * 3. Write stream to sink (console, files, tables)
 * 4. Manage query lifecycle (start, monitor, stop)
 *
 * File-based streaming is common for:
 * - Processing log files as they're written
 * - Ingesting data exports from external systems
 * - Learning streaming concepts without infrastructure
 */
object Foo {

  /**
   * Process a stream of text files from a directory.
   *
   * This demonstrates the core streaming pattern that applies to all sources.
   * Only the source changes (files → Kafka → Kinesis), transformations stay the same.
   *
   * @param spark Active SparkSession
   * @param sourcePath Directory to monitor for new files
   * @param checkpointPath Directory for checkpointing (fault tolerance)
   * @return StreamingQuery that can be monitored and stopped
   */
  def processStream(
    spark: SparkSession,
    sourcePath: String,
    checkpointPath: String
  ): StreamingQuery = {

    // Read streaming DataFrame from text files
    // Spark monitors sourcePath and processes new files as they arrive
    val streamDF = spark.readStream
      .format("text")  // Each line becomes a row
      .option("maxFilesPerTrigger", 1)  // Process 1 file per micro-batch (rate limiting)
      .load(sourcePath)
    // Note: This creates a streaming DataFrame with schema: value: string

    // Transform streaming data (same API as batch DataFrames)
    // Add processing timestamp and convert to uppercase
    val transformedDF = streamDF
      .withColumn("processed_time", current_timestamp())  // Add processing timestamp
      .withColumn("upper_value", upper(col("value")))     // Transform to uppercase
      .filter(length(col("value")) > 0)                    // Filter empty lines
    // Transformations are lazy - don't execute until we start the query

    // Write stream to console output (for demonstration)
    // In production: write to Parquet, Delta Lake, Kafka, database, etc.
    val query = transformedDF.writeStream
      .format("console")                                   // Output sink type
      .outputMode("append")                                // Only output new rows
      .option("checkpointLocation", checkpointPath)        // Fault tolerance
      .option("truncate", false)                           // Don't truncate long strings
      .trigger(Trigger.ProcessingTime("5 seconds"))        // Micro-batch every 5 seconds
      .start()
    // query.start() returns immediately - query runs in background

    query
  }

  /**
   * Demonstrate streaming aggregation with window functions.
   *
   * This shows how to aggregate streaming data over time windows.
   * Common for metrics, dashboards, and time-series analysis.
   *
   * @param spark Active SparkSession
   * @param sourcePath Directory to monitor
   * @param checkpointPath Checkpoint directory
   * @return StreamingQuery with aggregations
   */
  def streamingAggregation(
    spark: SparkSession,
    sourcePath: String,
    checkpointPath: String
  ): StreamingQuery = {

    // Read streaming data
    val streamDF = spark.readStream
      .format("text")
      .load(sourcePath)

    // Count lines and calculate string length statistics
    // This demonstrates streaming aggregations
    val statsDF = streamDF
      .withColumn("line_length", length(col("value")))
      .groupBy()  // Global aggregation (no grouping key)
      .agg(
        count("*").alias("total_lines"),           // Total lines processed
        avg("line_length").alias("avg_length"),    // Average line length
        max("line_length").alias("max_length")     // Maximum line length
      )
    // Note: Streaming aggregations require "complete" or "update" output mode

    val query = statsDF.writeStream
      .format("console")
      .outputMode("complete")  // Output entire result table (required for aggregations without watermark)
      .option("checkpointLocation", checkpointPath)
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start()

    query
  }

  def main(args: Array[String]): Unit = {
    // Initialize SparkSession for streaming
    val spark = SparkSession.builder()
      .appName("StreamingExample")
      .master("local[*]")  // Use all cores for parallel processing
      .getOrCreate()

    // Determine source path (command-line arg or resources)
    // For demonstration, we'll create sample data in a temp directory
    val sourcePath = if (args.length > 0) {
      args(0)
    } else {
      // Use temp directory for demo
      val tempDir = java.nio.file.Files.createTempDirectory("spark-streaming")
      val sourceDir = tempDir.resolve("input")
      java.nio.file.Files.createDirectories(sourceDir)

      // Create sample data file
      val sampleFile = sourceDir.resolve("sample1.txt")
      java.nio.file.Files.write(sampleFile,
        java.util.Arrays.asList(
          "Sample streaming data from file 1",
          "Processing real-time events",
          "Spark Structured Streaming is powerful"
        ))

      println(s"Using temporary source directory: $sourceDir")
      sourceDir.toString
    }

    // Checkpoint path for fault tolerance
    val checkpointPath = java.nio.file.Files.createTempDirectory("spark-checkpoint").toString
    println(s"Checkpoint location: $checkpointPath")

    // Start streaming query
    val query = processStream(spark, sourcePath, checkpointPath)

    // Monitor query status
    println(s"Query started: ${query.id}")
    println("Processing streaming data... (will run for 30 seconds)")
    println("Add files to the source directory to see them processed")

    // In production, query runs indefinitely
    // For demonstration, run for 30 seconds then stop
    Thread.sleep(30000)

    // Stop the query gracefully
    println("Stopping streaming query...")
    query.stop()

    // Wait for query to fully stop
    query.awaitTermination(5000)
    println("Query stopped")

    spark.stop()
  }
}
