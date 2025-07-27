package example

import org.apache.spark.sql.{SparkSession, functions => F}

/**
 * A simple ETL example that reads a CSV file, casts the price column to
 * double, calculates a price with tax, writes the result as Parquet and
 * prints a few rows. The input and output paths can be supplied via
 * `--input` and `--output` arguments; defaults are `example.csv` and
 * `out`.
 */
object CsvETL {
  def main(args: Array[String]): Unit = {
    // parse simple --input and --output arguments with defaults
    val inputPath = args.sliding(2).find(_(0) == "--input").map(_(1)).getOrElse("example.csv")
    val outputPath = args.sliding(2).find(_(0) == "--output").map(_(1)).getOrElse("out")

    val spark = SparkSession.builder().appName("CsvETL").getOrCreate()
    // Note the space after the comma in option("header", "true") for style compliance
    val df = spark.read.option("header", "true").csv(inputPath)

    val out = df
      .select(F.col("name"), F.col("price").cast("double"))
      .withColumn("price_with_tax", F.col("price") * 1.21)

    out.write.mode("overwrite").parquet(outputPath)
    out.show(5, truncate = false)
    spark.stop()
  }
}