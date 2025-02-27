package foo

import utest._
import org.apache.spark.sql.SparkSession
import scala.io.Source

object FooTests extends TestSuite {
  val spark: SparkSession = SparkSession.builder()
    .appName("SQL Analytics Tests")
    .master("local[*]")
    .getOrCreate()

  // Sample test data matching production schema
  val testData: String =
    """sale_id,date,product_id,product_name,amount,region,customer_id,quantity,payment_method
      |1,2023-01-01,P001,Product 1,100.0,North,cust1,1,Credit
      |2,2023-01-02,P002,Product 2,200.0,South,cust2,2,Debit
      |3,2023-01-03,P001,Product 1,300.0,North,cust3,3,Cash
      |4,2023-02-01,P003,Product 3,50.0,East,cust4,4,Online
      |5,2023-02-02,P002,Product 2,150.0,South,cust5,5,Debit
    """.stripMargin

  override def utestBeforeEach(path: Seq[String]): Unit = {
    import spark.implicits._

    // Create test DataFrame from CSV string
    import spark.implicits._
    val df = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(spark.createDataset(testData.split("\n").toSeq))

    df.createOrReplaceTempView("sales")
  }

  override def tests: Tests = Tests {
    test("DataLoadingTest") {
      val df = spark.sql("SELECT * FROM sales")

      // Verify schema
      val expectedColumns = Set(
        "sale_id",
        "date",
        "product_id",
        "product_name",
        "amount",
        "region",
        "customer_id",
        "quantity",
        "payment_method"
      )
      assert(df.columns.toSet == expectedColumns)

      // Verify row count
      assert(df.count() == 5)
    }

    test("SalesByRegionAnalysis") {
      val query = Source.fromResource("queries/sales_by_region.sql").mkString
      val result = spark.sql(query).collect()

      // Verify aggregation and sorting
      val expected = Seq(
        ("North", 400.0),
        ("South", 350.0),
        ("East", 50.0)
      )

      assert(result.map(r => (r.getString(0), r.getDouble(1))).toSeq == expected)
    }

    test("TopProductsAnalysis") {
      val query = Source.fromResource("queries/top_products.sql").mkString
      val result = spark.sql(query).collect()

      // Verify ranking and totals
      val expected = Seq(
        ("P001", "Product 1", 400.0, 1),
        ("P002", "Product 2", 350.0, 2),
        ("P003", "Product 3", 50.0, 3)
      )

      assert(result.map { r =>
        (r.getString(0), r.getString(1), r.getDouble(2), r.getInt(3))
      }.toSeq == expected)
    }

    test("MonthlyTrendAnalysis") {
      val query = Source.fromResource("queries/monthly_sales_trend.sql").mkString
      val result = spark.sql(query).collect()

      // Verify cumulative sums and monthly totals
      val expected = Seq(
        (2023, 1, 600.0, 600.0),
        (2023, 2, 200.0, 800.0)
      )

      assert(result.map { r =>
        (r.getInt(0), r.getInt(1), r.getDouble(2), r.getDouble(3))
      }.toSeq == expected)
    }
  }

  override def utestAfterAll(): Unit = {
    spark.stop()
  }
}
