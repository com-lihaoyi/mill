package foo

import org.apache.spark.sql.SparkSession
import utest._

/**
 * Test suite for User Defined Functions (UDFs).
 *
 * Testing UDFs ensures:
 * - Custom logic produces correct results
 * - Null handling works as expected
 * - Type conversions are accurate
 * - SQL registration works properly
 */
object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .appName("UDFTests")
    .master("local[*]")
    .getOrCreate()

  def tests = Tests {
    /**
     * Verify basic UDF calculates text length correctly.
     */
    test("basicUDF should calculate text length") {
      val result = Foo.basicUDF(spark)
      val results = result.collect()

      // Verify length column exists
      assert(result.columns.contains("length"))

      // Verify calculations for known data
      val row1 = results.find(_.getAs[Int]("id") == 1).get
      assert(row1.getAs[String]("text") == "hello world")
      assert(row1.getAs[Int]("length") == 11)

      val row2 = results.find(_.getAs[Int]("id") == 2).get
      assert(row2.getAs[String]("text") == "spark sql")
      assert(row2.getAs[Int]("length") == 9)
    }

    /**
     * Verify complex UDF combines multiple columns correctly.
     */
    test("complexUDF should combine text and value columns") {
      val result = Foo.complexUDF(spark)
      val results = result.collect()

      // Verify sentiment_score column exists
      assert(result.columns.contains("sentiment_score"))

      // Verify each row has a score
      results.foreach { row =>
        val score = row.getAs[Int]("sentiment_score")
        assert(score > 0)
      }

      // Verify score increases with text length and value
      val row1 = results.find(_.getAs[Int]("id") == 1).get
      val row3 = results.find(_.getAs[Int]("id") == 3).get

      // Row 3 has longer text and higher value, should have higher score
      assert(row3.getAs[Int]("sentiment_score") > row1.getAs[Int]("sentiment_score"))
    }

    /**
     * Verify SQL-registered UDF works in queries.
     */
    test("sqlUDF should work in SQL queries") {
      val result = Foo.sqlUDF(spark)
      val results = result.collect()

      // Verify upper_text column exists
      assert(result.columns.contains("upper_text"))

      // Verify uppercase transformation
      results.foreach { row =>
        val original = row.getAs[String]("text")
        val upper = row.getAs[String]("upper_text")
        assert(upper == original.toUpperCase)
      }
    }

    /**
     * Verify null-safe UDF handles missing data.
     */
    test("nullSafeUDF should handle null inputs") {
      val result = Foo.nullSafeUDF(spark)
      val results = result.collect()

      // Verify safe_length column exists
      assert(result.columns.contains("safe_length"))

      // All rows should have non-null safe_length (defaults to 0)
      results.foreach { row =>
        val safeLength = row.getAs[Int]("safe_length")
        assert(safeLength >= 0)
      }
    }

    /**
     * Verify UDF with complex return type.
     */
    test("structReturnUDF should return analysis struct") {
      val result = Foo.structReturnUDF(spark)
      val results = result.collect()

      // Verify analysis columns exist
      assert(result.columns.contains("char_count"))
      assert(result.columns.contains("word_count"))

      // Verify analysis for known data
      val row1 = results.find { row =>
        row.getAs[String]("text") == "hello world"
      }.get

      assert(row1.getAs[Int]("char_count") == 11)
      assert(row1.getAs[Int]("word_count") == 2)

      val row3 = results.find { row =>
        row.getAs[String]("text") == "user defined function"
      }.get

      assert(row3.getAs[Int]("char_count") == 21)
      assert(row3.getAs[Int]("word_count") == 3)
    }
  }

  sys.addShutdownHook {
    spark.stop()
  }
}
