package foo

import org.apache.spark.sql.SparkSession
import utest._
import foo.Foo
import foo.Foo.StockTick

object FooTests extends TestSuite {
  def tests = Tests {
    test("calculatePriceMovements should calculate correct price movements") {
      val spark = SparkSession.builder()
        .appName("StockAnalyticsTest")
        .master("local[*]")
        .getOrCreate()

      import spark.implicits._

      // Test data with known price changes
      val testData = Seq(
        StockTick("TEST", 100.0, 1000L),
        StockTick("TEST", 105.0, 2000L) // 5% increase
      ).toDF()

      val results = Foo.calculatePriceMovements(testData)

      // Verify results
      val rows = results.collect()
      assert(rows.length == 1)
      assert(rows(0).getString(0) == "TEST")
      assert(rows(0).getString(1).contains("5.0%"))
      assert(rows(0).getString(1).contains("?"))

      spark.stop()
    }

    test("generateMockStockData should create valid DataFrame") {
      val spark = SparkSession.builder()
        .appName("StockDataTest")
        .master("local[*]")
        .getOrCreate()

      val df = Foo.generateMockStockData(spark)

      assert(df.count() == 6)
      assert(df.columns.contains("symbol"))
      assert(df.columns.contains("price"))
      assert(df.columns.contains("timestamp"))

      spark.stop()
    }
  }
}
