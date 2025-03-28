package foo

import org.apache.spark.sql.{DataFrame, SparkSession, Row}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object Foo {

  /**
   * Analyze the quality of data in a DataFrame
   * Checks for null values and format issues
   */
  def analyzeDataQuality(spark: SparkSession, data: DataFrame): DataFrame = {
    import spark.implicits._

    // Create a validation result for each row
    val results = data.select("dataField").map { row =>
      val field = row.getAs[String](0)

      if (field == null) {
        // Check for null values
        ("null", false, "Null values")
      } else if (!isValidFormat(field)) {
        // Check for format issues
        (field, false, "Format issues")
      } else {
        // Valid data
        (field, true, null.asInstanceOf[String])
      }
    }.toDF("dataField", "isPassed", "failureReason")

    results
  }

  /**
   * Sample validation function - checks if data follows proper format
   * For this example, only "Product" is considered valid
   */
  def isValidFormat(value: String): Boolean = {
    value == "Product"
  }

  /**
   * Create sample data for demonstration
   */
  def createSampleData(spark: SparkSession): DataFrame = {
    import spark.implicits._

    val data = Seq(
      Row("Product"),
      Row(null),
      Row("Invalid Format")
    )

    val schema = StructType(Array(
      StructField("dataField", StringType, nullable = true)
    ))

    spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Data Quality Example")
      .master("local[*]")
      .getOrCreate()

    // Create sample data
    val sampleData = createSampleData(spark)

    // Analyze data quality
    val results = analyzeDataQuality(spark, sampleData)

    // Show results
    results.show()

    spark.stop()
  }
}
