package foo

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._

object Foo {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Assembly-Jar")
      .master("local[*]")
      .config("spark.ui.port", "4051")
      .getOrCreate()

    import spark.implicits._

    val resourcePath: String = args.headOption
      .orElse(Option(this.getClass.getResource("/input.txt")).map(_.getPath))
      .getOrElse(throw new RuntimeException(
        "input.txt not provided as argument and not found in resources"
      ))

    val df = loadTextFile(spark, resourcePath)
    val wordCountDf = countWords(df)

    wordCountDf.show(truncate = false)

    spark.stop()
  }

  // Load text file into DataFrame
  def loadTextFile(spark: SparkSession, path: String): DataFrame = {
    spark.read.textFile(path).toDF("line")
  }

  // Count words using DataFrame API
  def countWords(df: DataFrame): DataFrame = {
    df.select(explode(split(col("line"), "\\s+")).alias("word"))
      .groupBy("word")
      .count()
      .orderBy(desc("count"))
  }
}
