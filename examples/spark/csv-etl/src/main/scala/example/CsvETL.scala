package example

import org.apache.spark.sql.{SparkSession, functions => F}

object CsvETL {
  def main(args: Array[String]): Unit = {
    val inputPath = args.sliding(2).find(_(0) == "--input").map(_(1)).getOrElse("example.csv")
    val outputPath = args.sliding(2).find(_(0) == "--output").map(_(1)).getOrElse("out")
    val spark = SparkSession.builder().appName("CsvETL").getOrCreate()
    val df = spark.read.option("header", "true").csv(inputPath)
    val out = df.select(F.col("name"), F.col("price").cast("double"))
      .withColumn("price_with_tax", F.col("price") * 1.21)
    out.write.mode("overwrite").parquet(outputPath)
    out.show(false)
    spark.stop()
  }
}
