package foo

import org.apache.spark.sql.{SparkSession, DataFrame}

/**
 * Spark SQL example demonstrating complex queries with joins, aggregations, and window functions.
 *
 * This shows how to use SQL to query Spark DataFrames, bridging the gap between
 * traditional SQL databases and distributed data processing.
 *
 * Most Spark users interact primarily through SQL rather than DataFrame API,
 * making this the most important interface to understand.
 */
object Foo {

  /**
   * Load CSV data and register as SQL tables.
   *
   * This demonstrates the pattern for making data queryable via SQL:
   * 1. Read data into DataFrames
   * 2. Create temporary views (SQL table names)
   * 3. Query using standard SQL
   *
   * @param spark Active SparkSession
   * @param customersPath Path to customers CSV
   * @param ordersPath Path to orders CSV
   */
  def loadTables(spark: SparkSession, customersPath: String, ordersPath: String): Unit = {
    // Read customers CSV into DataFrame
    val customersDF = spark.read
      .option("header", "true")        // First row is column names
      .option("inferSchema", "true")   // Auto-detect types
      .csv(customersPath)

    // Register as temporary view for SQL queries
    // View name becomes table name in SQL
    customersDF.createOrReplaceTempView("customers")
    // Note: View is session-scoped - disappears when Spark session ends

    // Read orders CSV
    val ordersDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(ordersPath)

    // Register orders table
    ordersDF.createOrReplaceTempView("orders")

    println("Tables registered:")
    spark.catalog.listTables().show()
  }

  /**
   * Execute SQL join query combining customers and orders.
   *
   * This demonstrates:
   * - JOIN syntax (same as PostgreSQL/MySQL)
   * - GROUP BY aggregations
   * - Multiple aggregate functions in one query
   *
   * @param spark Active SparkSession
   * @return DataFrame with customer order summaries
   */
  def customerOrderSummary(spark: SparkSession): DataFrame = {
    // SQL query with JOIN and aggregations
    // This syntax is identical to PostgreSQL/MySQL
    spark.sql("""
      SELECT
        c.name,
        COUNT(o.order_id) as order_count,
        SUM(o.amount) as total_spent,
        AVG(o.amount) as avg_order_value
      FROM customers c
      JOIN orders o ON c.customer_id = o.customer_id
      GROUP BY c.name
      ORDER BY total_spent DESC
    """)
    // Catalyst optimizer automatically chooses join strategy
    // (broadcast join for small tables, shuffle join for large)
  }

  /**
   * Use window functions to rank orders by customer.
   *
   * Window functions enable advanced analytics:
   * - Ranking within groups
   * - Running totals
   * - Moving averages
   * - Lag/lead for time-series
   *
   * @param spark Active SparkSession
   * @return DataFrame with order rankings
   */
  def orderRankings(spark: SparkSession): DataFrame = {
    // ROW_NUMBER() assigns sequential number within each customer's orders
    // PARTITION BY creates separate ranking per customer
    // ORDER BY determines ranking order
    spark.sql("""
      SELECT
        customer_id,
        order_id,
        product,
        amount,
        order_date,
        ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date) as order_sequence,
        SUM(amount) OVER (PARTITION BY customer_id ORDER BY order_date) as running_total
      FROM orders
      ORDER BY customer_id, order_date
    """)
    // Window functions execute in parallel - Spark partitions by window key
  }

  /**
   * Use CTE (Common Table Expression) for readable complex query.
   *
   * CTEs break complex logic into named, reusable steps.
   * Catalyst optimizer merges CTEs - no performance penalty.
   *
   * @param spark Active SparkSession
   * @return DataFrame with high-value customers
   */
  def highValueCustomers(spark: SparkSession): DataFrame = {
    // WITH clause creates temporary named result set
    // Same syntax as PostgreSQL
    spark.sql("""
      WITH customer_totals AS (
        SELECT
          c.customer_id,
          c.name,
          c.city,
          SUM(o.amount) as lifetime_value
        FROM customers c
        JOIN orders o ON c.customer_id = o.customer_id
        GROUP BY c.customer_id, c.name, c.city
      )
      SELECT
        name,
        city,
        lifetime_value
      FROM customer_totals
      WHERE lifetime_value > 1000
      ORDER BY lifetime_value DESC
    """)
    // CTE makes query readable but doesn't materialize intermediate results
  }

  /**
   * Demonstrate subqueries for filtering.
   *
   * Subqueries allow using query results in WHERE/HAVING clauses.
   *
   * @param spark Active SparkSession
   * @return DataFrame with frequent buyers
   */
  def frequentBuyers(spark: SparkSession): DataFrame = {
    // Subquery in WHERE clause
    // Finds customers with more than 1 order
    spark.sql("""
      SELECT
        name,
        city
      FROM customers
      WHERE customer_id IN (
        SELECT customer_id
        FROM orders
        GROUP BY customer_id
        HAVING COUNT(*) > 1
      )
    """)
    // Catalyst optimizer may rewrite this as a join for better performance
  }

  def main(args: Array[String]): Unit = {
    // Initialize SparkSession with SQL support
    val spark = SparkSession.builder()
      .appName("SparkSQLExample")
      .master("local[*]")
      .getOrCreate()

    // Enable implicit conversions for $-notation in DataFrames
    import spark.implicits._

    // Determine data paths (command-line args or resources)
    val customersPath = args.headOption.getOrElse(
      Option(getClass.getResource("/customers.csv")).map(_.getPath)
        .getOrElse(throw new RuntimeException("customers.csv not found"))
    )

    val ordersPath = if (args.length > 1) args(1) else {
      Option(getClass.getResource("/orders.csv")).map(_.getPath)
        .getOrElse(throw new RuntimeException("orders.csv not found"))
    }

    // Load data and register SQL tables
    println("Loading tables...")
    loadTables(spark, customersPath, ordersPath)
    println()

    // Execute various SQL queries
    println("Customer Order Summary:")
    val summary = customerOrderSummary(spark)
    summary.show(truncate = false)
    println()

    println("Order Rankings (with running total):")
    val rankings = orderRankings(spark)
    rankings.show(truncate = false)
    println()

    println("High-Value Customers (>$1000):")
    val highValue = highValueCustomers(spark)
    highValue.show(truncate = false)
    println()

    println("Frequent Buyers (>1 order):")
    val frequent = frequentBuyers(spark)
    frequent.show(truncate = false)

    spark.stop()
  }
}
