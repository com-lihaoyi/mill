package foo

import org.apache.spark.sql.SparkSession
import utest._

/**
 * Test suite for Spark SQL complex queries.
 *
 * Testing SQL queries verifies:
 * - Joins produce correct results
 * - Aggregations calculate accurately
 * - Window functions work as expected
 * - CTEs and subqueries execute properly
 */
object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .appName("SparkSQLTests")
    .master("local[*]")
    .getOrCreate()

  def tests = Tests {
    /**
     * Verify customer order summary query produces correct aggregations.
     */
    test("customerOrderSummary should aggregate orders by customer") {
      // Create test data in memory
      import spark.implicits._

      val customers = Seq(
        (1, "Alice", "NYC"),
        (2, "Bob", "LA")
      ).toDF("customer_id", "name", "city")

      val orders = Seq(
        (101, 1, "Laptop", 1000.0),
        (102, 1, "Mouse", 50.0),
        (103, 2, "Keyboard", 100.0)
      ).toDF("order_id", "customer_id", "product", "amount")

      // Register tables
      customers.createOrReplaceTempView("customers")
      orders.createOrReplaceTempView("orders")

      // Execute query
      val result = Foo.customerOrderSummary(spark)

      // Collect results for assertions
      val results = result.collect()

      // Verify we got 2 customers
      assert(results.length == 2)

      // Verify Alice's totals
      val alice = results.find(_.getAs[String]("name") == "Alice").get
      assert(alice.getAs[Long]("order_count") == 2)
      assert(alice.getAs[Double]("total_spent") == 1050.0)

      // Verify Bob's totals
      val bob = results.find(_.getAs[String]("name") == "Bob").get
      assert(bob.getAs[Long]("order_count") == 1)
      assert(bob.getAs[Double]("total_spent") == 100.0)
    }

    /**
     * Verify window functions calculate rankings correctly.
     */
    test("orderRankings should calculate row numbers and running totals") {
      import spark.implicits._

      val customers = Seq(
        (1, "Alice", "NYC")
      ).toDF("customer_id", "name", "city")

      val orders = Seq(
        (101, 1, "Item1", 100.0, "2023-01-01"),
        (102, 1, "Item2", 200.0, "2023-01-02"),
        (103, 1, "Item3", 150.0, "2023-01-03")
      ).toDF("order_id", "customer_id", "product", "amount", "order_date")

      customers.createOrReplaceTempView("customers")
      orders.createOrReplaceTempView("orders")

      val result = Foo.orderRankings(spark)
      val results = result.collect().sortBy(_.getAs[Int]("order_sequence"))

      // Verify sequence numbers
      assert(results(0).getAs[Int]("order_sequence") == 1)
      assert(results(1).getAs[Int]("order_sequence") == 2)
      assert(results(2).getAs[Int]("order_sequence") == 3)

      // Verify running totals
      assert(results(0).getAs[Double]("running_total") == 100.0)
      assert(results(1).getAs[Double]("running_total") == 300.0)  // 100 + 200
      assert(results(2).getAs[Double]("running_total") == 450.0)  // 300 + 150
    }

    /**
     * Verify CTE query filters high-value customers.
     */
    test("highValueCustomers should filter by lifetime value") {
      import spark.implicits._

      val customers = Seq(
        (1, "Alice", "NYC"),
        (2, "Bob", "LA"),
        (3, "Carol", "Chicago")
      ).toDF("customer_id", "name", "city")

      val orders = Seq(
        (101, 1, "Laptop", 1500.0),  // Alice: 1500 (high value)
        (102, 2, "Mouse", 50.0),      // Bob: 50 (low value)
        (103, 3, "Monitor", 800.0),   // Carol: 1100 (high value)
        (104, 3, "Keyboard", 300.0)
      ).toDF("order_id", "customer_id", "product", "amount")

      customers.createOrReplaceTempView("customers")
      orders.createOrReplaceTempView("orders")

      val result = Foo.highValueCustomers(spark)
      val results = result.collect()

      // Should return Alice and Carol (>$1000), not Bob
      assert(results.length == 2)
      val names = results.map(_.getAs[String]("name")).toSet
      assert(names.contains("Alice"))
      assert(names.contains("Carol"))
      assert(!names.contains("Bob"))
    }

    /**
     * Verify subquery identifies frequent buyers.
     */
    test("frequentBuyers should find customers with multiple orders") {
      import spark.implicits._

      val customers = Seq(
        (1, "Alice", "NYC"),
        (2, "Bob", "LA"),
        (3, "Carol", "Chicago")
      ).toDF("customer_id", "name", "city")

      val orders = Seq(
        (101, 1, "Item1", 100.0),  // Alice: 2 orders (frequent)
        (102, 1, "Item2", 200.0),
        (103, 2, "Item3", 150.0)   // Bob: 1 order (not frequent)
      ).toDF("order_id", "customer_id", "product", "amount")

      customers.createOrReplaceTempView("customers")
      orders.createOrReplaceTempView("orders")

      val result = Foo.frequentBuyers(spark)
      val results = result.collect()

      // Should return only Alice (>1 order)
      assert(results.length == 1)
      assert(results(0).getAs[String]("name") == "Alice")
    }
  }

  sys.addShutdownHook {
    spark.stop()
  }
}
