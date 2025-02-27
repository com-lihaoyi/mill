package foo

import org.apache.spark.sql.SparkSession
import scala.io.Source
import java.io.File

object Foo {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("SQL Analytics")
      .master("local[*]")
      .getOrCreate()

    try {
      // Load embedded CSV data
      val df = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .csv(getClass.getResource("/data/sales.csv").getPath)

      df.createOrReplaceTempView("sales")

      // Directory containing SQL queries
      val queryDir = new File(getClass.getResource("/queries").getPath)

      if (queryDir.exists() && queryDir.isDirectory) {
        val sqlFiles = queryDir.listFiles().filter(_.getName.endsWith(".sql"))

        for (file <- sqlFiles) {
          val analysisName = file.getName.stripSuffix(".sql")
          println(s"Running analysis: $analysisName")

          val query = Source.fromFile(file).mkString
          val result = spark.sql(query)

          println(s"Results for $analysisName:")
          result.show(truncate = false)
        }
      } else {
        println("No queries folder found or it's empty.")
      }
    } finally {
      spark.stop()
    }
  }
}
