package foo

import org.apache.spark.sql.SparkSession
import utest.*

object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .appName("SimpleRealisticTests")
    .master("local[*]")
    .getOrCreate()

  import spark.implicits.*

  def tests = Tests {
    test("computeSummary should compute correct summary statistics") {
      val transactions = Seq(
        Foo.Transaction(1, "Food", 20.5),
        Foo.Transaction(2, "Electronics", 250.0),
        Foo.Transaction(3, "Food", 35.0),
        Foo.Transaction(4, "Clothing", 45.5),
        Foo.Transaction(5, "Food", 15.0),
        Foo.Transaction(6, "Electronics", 125.0),
        Foo.Transaction(7, "Clothing", 75.0)
      )

      val ds = transactions.toDS()
      val summaryDF = Foo.computeSummary(ds)

      def approxEqual(a: Double, b: Double, tol: Double = 0.001): Boolean =
        math.abs(a - b) < tol

      val results = summaryDF.collect().map { row =>
        val category = row.getAs[String]("category")
        val total = row.getAs[Double]("total_amount")
        val average = row.getAs[Double]("average_amount")
        val count = row.getAs[Long]("transaction_count")
        category -> (total, average, count)
      }.toMap

      println(results.get("Food"))
      results.get("Food").foreach { case (total, average, count) =>
        assert(approxEqual(total, 70.5))
        assert(approxEqual(average, 70.5 / 3))
        assert(count == 3)
      }

      results.get("Electronics").foreach { case (total, average, count) =>
        assert(approxEqual(total, 375.0))
        assert(approxEqual(average, 375.0 / 2))
        assert(count == 2)
      }

      results.get("Clothing").foreach { case (total, average, count) =>
        assert(approxEqual(total, 120.5))
        assert(approxEqual(average, 120.5 / 2))
        assert(count == 2)
      }

    }
  }

  // Ensure that Spark is stopped after tests complete
  sys.addShutdownHook {
    spark.stop()
  }
}
