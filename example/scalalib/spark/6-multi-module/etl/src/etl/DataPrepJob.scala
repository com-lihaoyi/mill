package etl

import common.SparkUtils
import common.SparkUtils.User
import org.apache.spark.sql.functions._

object DataPrepJob {

  def main(args: Array[String]): Unit = {
    val spark = SparkUtils.createLocalSession("DataPrepJob")
    import spark.implicits._

    println("=== ETL Job: Preparing user data ===")

    // Create DataFrame from shared sample data
    val usersDF = SparkUtils.sampleUsers.toDF()

    // Apply some transformations (simulating ETL)
    val preparedDF = usersDF
      .filter($"age" >= 18) // Filter valid users
      .withColumn(
        "age_group",
        when($"age" < 30, "young")
          .when($"age" < 50, "middle")
          .otherwise("senior")
      )

    preparedDF.show()

    // In a real application, you would write to a data lake or warehouse
    println(s"Prepared ${preparedDF.count()} user records")

    spark.stop()
  }
}
