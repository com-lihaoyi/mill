package foo

import org.apache.spark.sql.SparkSession
import utest._

/**
 * Test suite for advanced DataFrame operations.
 *
 * Testing complex DataFrame logic ensures:
 * - Pivot tables aggregate correctly
 * - Window functions calculate accurately
 * - Multi-level grouping produces expected results
 * - Null handling works as designed
 * - Conditional logic categorizes properly
 */
object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .appName("AdvancedDataFrameTests")
    .master("local[*]")
    .getOrCreate()

  def tests = Tests {
    /**
     * Verify pivot table creates cross-tabulation correctly.
     */
    test("pivotTable should aggregate revenue by region and product") {
      val pivotDF = Foo.createPivotTable(spark)
      val results = pivotDF.collect()

      // Should have one row per region
      assert(results.length == 2)

      // Verify structure has region + product columns
      val columns = pivotDF.columns.toSet
      assert(columns.contains("region"))
      assert(columns.contains("Laptop") || columns.contains("Phone"))

      // Verify revenue calculations are present
      val eastRow = results.find(_.getAs[String]("region") == "East")
      assert(eastRow.isDefined)
    }

    /**
     * Verify window functions calculate running totals correctly.
     */
    test("windowFunctions should calculate running totals per partition") {
      val runningDF = Foo.calculateRunningTotals(spark)
      val results = runningDF.collect()

      // Should have same number of rows as input
      assert(results.length == 6)

      // Verify running_total column exists
      val columns = runningDF.columns.toSet
      assert(columns.contains("running_total"))

      // Verify running totals are cumulative (later dates >= earlier dates within region)
      val eastOrders = results.filter(_.getAs[String]("region") == "East")
        .sortBy(_.getAs[String]("date"))

      if (eastOrders.length > 1) {
        val firstTotal = eastOrders(0).getAs[Double]("running_total")
        val secondTotal = eastOrders(1).getAs[Double]("running_total")
        assert(secondTotal >= firstTotal)
      }
    }

    /**
     * Verify multi-level aggregation groups by multiple columns.
     */
    test("multiLevelAggregation should aggregate by region and category") {
      val aggDF = Foo.multiLevelAggregation(spark)
      val results = aggDF.collect()

      // Should have results for each region × category combination
      assert(results.length > 0)

      // Verify aggregation columns exist
      val columns = aggDF.columns.toSet
      assert(columns.contains("region"))
      assert(columns.contains("category"))
      assert(columns.contains("total_revenue"))
      assert(columns.contains("avg_price"))
      assert(columns.contains("total_quantity"))
      assert(columns.contains("transaction_count"))

      // Verify aggregations are numeric
      results.foreach { row =>
        assert(row.getAs[Double]("total_revenue") >= 0)
        assert(row.getAs[Long]("transaction_count") > 0)
      }
    }

    /**
     * Verify null handling strategies work correctly.
     */
    test("handleNulls should replace missing values") {
      val handledDF = Foo.handleNulls(spark)
      val results = handledDF.collect()

      // Should have same number of rows as input
      assert(results.length == 6)

      // Verify safe columns exist
      val columns = handledDF.columns.toSet
      assert(columns.contains("safe_quantity"))
      assert(columns.contains("safe_price"))

      // Safe columns should have no nulls (all replaced with defaults)
      results.foreach { row =>
        assert(row.getAs[Any]("safe_quantity") != null)
        assert(row.getAs[Any]("safe_price") != null)
      }
    }

    /**
     * Verify conditional columns categorize correctly.
     */
    test("conditionalColumns should add price tiers") {
      val conditionalDF = Foo.addConditionalColumns(spark)
      val results = conditionalDF.collect()

      // Verify new columns exist
      val columns = conditionalDF.columns.toSet
      assert(columns.contains("price_tier"))
      assert(columns.contains("high_volume"))

      // Verify price tier logic
      results.foreach { row =>
        val price = row.getAs[Double]("price")
        val tier = row.getAs[String]("price_tier")

        if (price > 1000) {
          assert(tier == "premium")
        } else if (price > 500) {
          assert(tier == "standard")
        } else {
          assert(tier == "budget")
        }
      }

      // Verify boolean logic
      results.foreach { row =>
        val quantity = row.getAs[Int]("quantity")
        val highVolume = row.getAs[Boolean]("high_volume")
        assert(highVolume == (quantity > 5))
      }
    }
  }

  sys.addShutdownHook {
    spark.stop()
  }
}
