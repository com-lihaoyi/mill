package foo

import org.apache.spark.sql.{SparkSession, DataFrame, SaveMode}

object Foo {

  /**
   * Demonstrate CSV → Parquet conversion with performance comparison.
   */
  def convertCSVToParquet(spark: SparkSession, csvPath: String, parquetPath: String): Unit = {
    // Read CSV (slow, row-based)
    val start = System.currentTimeMillis()
    val df = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(csvPath)
    df.cache()  // Cache to measure read time accurately
    val csvCount = df.count()  // Force evaluation
    val csvTime = System.currentTimeMillis() - start
    println(s"CSV read: $csvCount rows in ${csvTime}ms")

    // Write as Parquet (fast, columnar, compressed)
    val writeStart = System.currentTimeMillis()
    df.write
      .mode(SaveMode.Overwrite)
      .option("compression", "snappy")  // Balanced compression
      .parquet(parquetPath)
    val writeTime = System.currentTimeMillis() - writeStart
    println(s"Parquet write: ${writeTime}ms")
  }

  /**
   * Compare query performance: CSV vs Parquet.
   */
  def comparePerformance(spark: SparkSession, csvPath: String, parquetPath: String): DataFrame = {
    // Query CSV
    val csvStart = System.currentTimeMillis()
    val csvDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(csvPath)
      .filter("age > 30")
      .select("name", "department")
    val csvResult = csvDF.collect()  // Force execution
    val csvTime = System.currentTimeMillis() - csvStart

    // Query Parquet (should be faster)
    val parquetStart = System.currentTimeMillis()
    val parquetDF = spark.read
      .parquet(parquetPath)
      .filter("age > 30")
      .select("name", "department")
    val parquetResult = parquetDF.collect()
    val parquetTime = System.currentTimeMillis() - parquetStart

    println(s"CSV query: ${csvTime}ms")
    println(s"Parquet query: ${parquetTime}ms")
    println(s"Speedup: ${csvTime.toDouble / parquetTime}x faster")

    parquetDF
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("ParquetExample")
      .master("local[*]")
      .getOrCreate()

    val csvPath = args.headOption.getOrElse(
      Option(getClass.getResource("/sample_data.csv")).map(_.getPath)
        .getOrElse(throw new RuntimeException("sample_data.csv not found"))
    )

    val parquetPath = java.nio.file.Files.createTempDirectory("parquet-output").toString

    println("Converting CSV to Parquet...")
    convertCSVToParquet(spark, csvPath, parquetPath)

    println("\nComparing query performance...")
    comparePerformance(spark, csvPath, parquetPath)

    spark.stop()
  }
}
