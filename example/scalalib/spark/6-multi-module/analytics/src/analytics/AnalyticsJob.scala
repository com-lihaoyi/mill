package analytics

import common.SparkUtils
import org.apache.spark.sql.functions._

object AnalyticsJob {

  def main(args: Array[String]): Unit = {
    val spark = SparkUtils.createLocalSession("AnalyticsJob")
    import spark.implicits._

    println("=== Analytics Job: Computing metrics ===")

    // Create DataFrame from shared sample data
    val usersDF = SparkUtils.sampleUsers.toDF()

    // Compute aggregated metrics
    val metrics = usersDF
      .groupBy($"active")
      .agg(
        count("*").alias("user_count"),
        round(avg($"age"), 1).alias("avg_age")
      )
      .orderBy($"active".desc)

    metrics.show()

    // Additional analytics
    println("=== Age distribution ===")
    usersDF
      .select(
        min($"age").alias("min_age"),
        max($"age").alias("max_age"),
        round(avg($"age"), 1).alias("avg_age"),
        round(stddev($"age"), 1).alias("stddev_age")
      )
      .show()

    spark.stop()
  }
}
