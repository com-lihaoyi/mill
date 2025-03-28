package foo

import org.apache.spark.sql.{DataFrame, SparkSession, Row}
import org.apache.spark.sql.types._
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("basic tests should analyze data quality correctly") {
      val spark = SparkSession.builder()
        .appName("DataQualityTest")
        .master("local[*]")
        .getOrCreate()

      // Create test data
      val testData = createTestData(spark)

      // Run the data quality analysis
      val results = Foo.analyzeDataQuality(spark, testData)

      // Convert results to a list for assertions
      val resultsList = results.collect().map { row =>
        (row.getString(0), row.getBoolean(1), Option(row.getString(2)))
      }.toList

      // Verify each test case
      val expected = List(
        ("Product", true, None),
        ("null", false, Some("Null values")),
        ("Invalid Format", false, Some("Format issues"))
      )

      assert(resultsList == expected)

      // Stop the SparkSession
      spark.stop()
    }
  }

  /**
   * Create test data with the same structure as in the main class
   */
  private def createTestData(spark: SparkSession): DataFrame = {
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
}
