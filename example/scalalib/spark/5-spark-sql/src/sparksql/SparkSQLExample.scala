package sparksql

import org.apache.spark.sql.SparkSession

object SparkSQLExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("SparkSQLExample")
      .master("local[*]")
      .getOrCreate()

    // Read JSON data into a DataFrame
    val resourcePath = Option(getClass.getResource("/products.json"))
      .map(_.getPath)
      .getOrElse(throw new RuntimeException("products.json not found in resources"))

    val products = spark.read.json(resourcePath)

    // Register the DataFrame as a SQL temporary view
    products.createOrReplaceTempView("products")

    // Basic SELECT query
    println("=== All products ===")
    spark.sql("SELECT * FROM products ORDER BY id").show()

    // Filtering with WHERE clause
    println("=== Products over $200 ===")
    spark.sql("""
      SELECT name, category, price
      FROM products
      WHERE price > 200
      ORDER BY price DESC
    """).show()

    // Aggregation with GROUP BY
    println("=== Average price by category ===")
    spark.sql("""
      SELECT
        category,
        COUNT(*) as product_count,
        ROUND(AVG(price), 2) as avg_price,
        MIN(price) as min_price,
        MAX(price) as max_price
      FROM products
      GROUP BY category
      ORDER BY avg_price DESC
    """).show()

    // Window functions for ranking
    println("=== Rank products by price within category ===")
    spark.sql("""
      SELECT
        name,
        category,
        price,
        RANK() OVER (PARTITION BY category ORDER BY price DESC) as price_rank
      FROM products
      ORDER BY category, price_rank
    """).show()

    // Subquery example
    println("=== Products priced above category average ===")
    spark.sql("""
      SELECT p.name, p.category, p.price, cat_avg.avg_price
      FROM products p
      JOIN (
        SELECT category, AVG(price) as avg_price
        FROM products
        GROUP BY category
      ) cat_avg ON p.category = cat_avg.category
      WHERE p.price > cat_avg.avg_price
      ORDER BY p.category, p.price DESC
    """).show()

    // CASE expression
    println("=== Price tier classification ===")
    spark.sql("""
      SELECT
        name,
        price,
        CASE
          WHEN price >= 500 THEN 'Premium'
          WHEN price >= 200 THEN 'Standard'
          ELSE 'Budget'
        END as price_tier
      FROM products
      ORDER BY price DESC
    """).show()

    spark.stop()
  }
}
