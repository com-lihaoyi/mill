package foo

import org.apache.spark.sql.SparkSession
import utest._

/**
 * Test suite for semi-realistic Spark aggregation logic.
 *
 * This demonstrates testing best practices for Spark applications:
 * - Create SparkSession once for all tests (faster, avoids initialization overhead)
 * - Use local mode for deterministic, fast testing
 * - Test with small, known data to verify aggregation logic
 * - Use floating-point comparison with tolerance (avoid precision errors)
 */
object FooTests extends TestSuite {
  // Initialize SparkSession once for all tests
  // Reusing the same session across tests is much faster than creating new sessions
  val spark = SparkSession.builder()
    .appName("SimpleRealisticTests") // Identifies tests in Spark UI
    .master("local[*]") // Run locally using all CPU cores
    .getOrCreate()

  // Import implicits for Dataset conversions (.toDS())
  import spark.implicits._

  def tests = Tests {

    /**
     * Verify computeSummary correctly aggregates by category.
     *
     * Testing strategy:
     * 1. Create small, known dataset (7 transactions across 3 categories)
     * 2. Convert to Dataset[Transaction] (same type as production code)
     * 3. Call aggregation function
     * 4. Verify sum, average, and count for each category
     *
     * This tests the core business logic without requiring actual CSV files.
     */
    test("computeSummary should compute correct summary statistics") {
      // Create test data - 7 transactions across 3 categories
      // Food: 20.5, 35.0, 15.0 (total: 70.5, avg: 23.5, count: 3)
      // Electronics: 250.0, 125.0 (total: 375.0, avg: 187.5, count: 2)
      // Clothing: 45.5, 75.0 (total: 120.5, avg: 60.25, count: 2)
      val transactions = Seq(
        Foo.Transaction(1, "Food", 20.5),
        Foo.Transaction(2, "Electronics", 250.0),
        Foo.Transaction(3, "Food", 35.0),
        Foo.Transaction(4, "Clothing", 45.5),
        Foo.Transaction(5, "Food", 15.0),
        Foo.Transaction(6, "Electronics", 125.0),
        Foo.Transaction(7, "Clothing", 75.0)
      )

      // Convert Scala Seq to Spark Dataset (distributed collection)
      // .toDS() is provided by spark.implicits._
      val ds = transactions.toDS()

      // Call the aggregation function we're testing
      val summaryDF = Foo.computeSummary(ds)

      // Helper function for floating-point comparison
      // Necessary because floating-point arithmetic isn't exact (e.g., 0.1 + 0.2 ≠ 0.3)
      def approxEqual(a: Double, b: Double, tol: Double = 0.001): Boolean =
        math.abs(a - b) < tol

      // Collect results to driver and convert to Map for easy lookup
      // .collect() is safe here because we know the result is tiny (3 rows)
      // In production tests with larger results, use .take(n) instead
      val results = summaryDF.collect().map { row =>
        val category = row.getAs[String]("category")
        val total = row.getAs[Double]("total_amount")
        val average = row.getAs[Double]("average_amount")
        val count = row.getAs[Long]("transaction_count")
        category -> (total, average, count)
      }.toMap

      // Verify Food category aggregations
      println(results.get("Food")) // Debug output for troubleshooting
      results.get("Food").foreach { case (total, average, count) =>
        assert(approxEqual(total, 70.5)) // 20.5 + 35.0 + 15.0
        assert(approxEqual(average, 70.5 / 3)) // 23.5
        assert(count == 3)
      }

      // Verify Electronics category aggregations
      results.get("Electronics").foreach { case (total, average, count) =>
        assert(approxEqual(total, 375.0)) // 250.0 + 125.0
        assert(approxEqual(average, 375.0 / 2)) // 187.5
        assert(count == 2)
      }

      // Verify Clothing category aggregations
      results.get("Clothing").foreach { case (total, average, count) =>
        assert(approxEqual(total, 120.5)) // 45.5 + 75.0
        assert(approxEqual(average, 120.5 / 2)) // 60.25
        assert(count == 2)
      }

    }
  }

  // Ensure that Spark is stopped after tests complete
  // This shutdown hook releases resources when JVM exits
  // Critical for preventing resource leaks in test suites
  sys.addShutdownHook {
    spark.stop()
  }
}
