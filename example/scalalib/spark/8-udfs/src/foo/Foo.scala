package foo

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._

/**
 * User Defined Functions (UDFs) demonstrating custom transformations.
 *
 * UDFs extend Spark with domain-specific logic that doesn't exist
 * in the standard function library. Use sparingly - built-in functions
 * are always faster due to Catalyst optimization.
 */
object Foo {

  /**
   * Demonstrate basic UDF that calculates text length.
   *
   * This is a simple example - in production you'd use built-in length()
   * function instead. Shown here to illustrate UDF mechanics.
   *
   * @param spark Active SparkSession
   * @return DataFrame with length column added
   */
  def basicUDF(spark: SparkSession): DataFrame = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/data.csv").getPath)

    // Define UDF: takes String, returns Int
    // This is type-safe - compiler checks signatures
    val calculateLength = udf((text: String) => {
      if (text == null) 0
      else text.length
    })

    // Apply UDF to column
    data.withColumn("length", calculateLength(col("text")))
  }

  /**
   * Demonstrate complex UDF with multiple parameters.
   *
   * This shows UDF taking multiple columns as input and
   * performing custom calculation combining them.
   *
   * @param spark Active SparkSession
   * @return DataFrame with sentiment score
   */
  def complexUDF(spark: SparkSession): DataFrame = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/data.csv").getPath)

    // UDF with multiple parameters
    // Demonstrates domain-specific logic that doesn't exist in standard library
    val calculateSentiment = udf((text: String, value: Int) => {
      if (text == null) 0
      else {
        // Custom logic combining text analysis with numeric value
        val wordCount = text.split("\\s+").length
        val baseScore = wordCount * 2
        val adjustedScore = baseScore + (value / 10)
        adjustedScore
      }
    })

    data.withColumn("sentiment_score",
      calculateSentiment(col("text"), col("value")))
  }

  /**
   * Register UDF for use in SQL queries.
   *
   * Shows how to make UDFs available to Spark SQL,
   * enabling use in SQL strings alongside built-in functions.
   *
   * @param spark Active SparkSession
   * @return DataFrame from SQL query using registered UDF
   */
  def sqlUDF(spark: SparkSession): DataFrame = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/data.csv").getPath)

    // Register UDF with a name
    spark.udf.register("toUpperCase", (text: String) => {
      if (text == null) ""
      else text.toUpperCase
    })

    // Create temporary view for SQL
    data.createOrReplaceTempView("data")

    // Use registered UDF in SQL query
    spark.sql("""
      SELECT
        id,
        text,
        value,
        toUpperCase(text) as upper_text
      FROM data
    """)
  }

  /**
   * Demonstrate null-safe UDF using Option types.
   *
   * Production UDFs must handle null inputs gracefully.
   * Option types provide idiomatic Scala null handling.
   *
   * @param spark Active SparkSession
   * @return DataFrame with null-safe transformation
   */
  def nullSafeUDF(spark: SparkSession): DataFrame = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/data.csv").getPath)

    // UDF using Option for null safety
    val safeLength = udf((text: Option[String]) => {
      text.map(_.length).getOrElse(0)
    })

    data.withColumn("safe_length", safeLength(col("text")))
  }

  /**
   * Demonstrate UDF with complex return type.
   *
   * UDFs can return any serializable type including
   * case classes, tuples, arrays, and maps.
   *
   * @param spark Active SparkSession
   * @return DataFrame with struct column
   */
  def structReturnUDF(spark: SparkSession): DataFrame = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/data.csv").getPath)

    // UDF returning tuple (becomes struct in DataFrame)
    val analyzeText = udf((text: String) => {
      if (text == null) (0, 0)
      else {
        val length = text.length
        val wordCount = text.split("\\s+").length
        (length, wordCount)
      }
    })

    data.withColumn("analysis", analyzeText(col("text")))
      .select(col("text"), col("analysis._1").alias("char_count"),
        col("analysis._2").alias("word_count"))
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("UDFExample")
      .master("local[*]")
      .getOrCreate()

    println("Testing UDFs with sample data from resources/data.csv")
    println()

    // Basic UDF
    println("Basic UDF - Text length:")
    val basicDF = basicUDF(spark)
    basicDF.show(false)
    println()

    // Complex UDF
    println("Complex UDF - Sentiment score:")
    val complexDF = complexUDF(spark)
    complexDF.show(false)
    println()

    // SQL UDF
    println("SQL UDF - Registered function:")
    val sqlDF = sqlUDF(spark)
    sqlDF.show(false)
    println()

    // Struct return UDF
    println("Struct return UDF - Text analysis:")
    val structDF = structReturnUDF(spark)
    structDF.show(false)

    spark.stop()
  }
}