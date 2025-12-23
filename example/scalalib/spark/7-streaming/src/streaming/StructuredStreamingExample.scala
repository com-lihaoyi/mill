package streaming

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import java.util.concurrent.TimeUnit

object StructuredStreamingExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("StructuredStreamingExample")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()

    import spark.implicits._

    println("=== Starting Structured Streaming Example ===")

    // Create a streaming DataFrame using the 'rate' source
    // This generates rows with (timestamp, value) at 1 row per second
    val streamingDF = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "1")
      .load()

    // Transform the stream: add derived columns
    val enrichedDF = streamingDF
      .withColumn(
        "event_type",
        when($"value" % 3 === 0, "type_a")
          .when($"value" % 3 === 1, "type_b")
          .otherwise("type_c")
      )

    // Aggregate: count events by type
    val aggregatedDF = enrichedDF
      .groupBy($"event_type")
      .count()

    // Write the stream to console
    val query = aggregatedDF.writeStream
      .outputMode("complete") // Output full result table on each trigger
      .format("console") // Write to console for demonstration
      .trigger(Trigger.ProcessingTime(2, TimeUnit.SECONDS)) // Process every 2 seconds
      .start()

    // Run for a limited time for demonstration purposes
    // In production, you would use query.awaitTermination() without timeout
    query.awaitTermination(10000) // Wait 10 seconds

    println("=== Streaming query completed ===")
    spark.stop()
  }
}
