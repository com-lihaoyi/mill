package foo

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window

/**
 * Advanced DataFrame operations demonstrating complex analytics patterns.
 *
 * This shows operations that power production analytics:
 * - Pivot tables for cross-dimensional analysis
 * - Window functions for running calculations
 * - Multi-level aggregations for drill-down reports
 * - Null handling for data quality
 * - Conditional logic for derived columns
 */
object Foo {

  /**
   * Create pivot table showing revenue by region and product.
   *
   * Pivot tables reshape data for cross-tabulation analysis.
   * Common in dashboards showing metrics across two dimensions.
   *
   * @param spark Active SparkSession
   * @return DataFrame with regions as rows, products as columns
   */
  def createPivotTable(spark: SparkSession): DataFrame = {
    // Load sales data
    val salesDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/sales.csv").getPath)

    // Calculate revenue (quantity * price)
    val withRevenue = salesDF
      .withColumn("revenue", col("quantity") * col("price"))

    // Pivot: regions as rows, products as columns, sum revenue
    withRevenue
      .groupBy("region")
      .pivot("product") // Creates column per unique product
      .sum("revenue") // Aggregate function at intersections
  }

  /**
   * Calculate running totals using window functions.
   *
   * Window functions allow aggregations without collapsing rows.
   * Essential for time-series analysis and cumulative metrics.
   *
   * @param spark Active SparkSession
   * @return DataFrame with running totals by region
   */
  def calculateRunningTotals(spark: SparkSession): DataFrame = {
    val salesDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/sales.csv").getPath)

    val withRevenue = salesDF
      .withColumn("revenue", col("quantity") * col("price"))

    // Define window: partition by region, order by date
    // Each region gets independent running total
    val windowSpec = Window
      .partitionBy("region")
      .orderBy("date")
      .rowsBetween(Window.unboundedPreceding, Window.currentRow)

    // Calculate running total within each region
    withRevenue
      .withColumn("running_total", sum("revenue").over(windowSpec))
      .select("date", "region", "product", "revenue", "running_total")
  }

  /**
   * Perform multi-level aggregation (region + category).
   *
   * Demonstrates grouping by multiple columns for drill-down analysis.
   * Foundation for OLAP cubes and dimensional warehouses.
   *
   * @param spark Active SparkSession
   * @return DataFrame with aggregated metrics by region and category
   */
  def multiLevelAggregation(spark: SparkSession): DataFrame = {
    val salesDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/sales.csv").getPath)

    val withRevenue = salesDF
      .withColumn("revenue", col("quantity") * col("price"))

    // Group by two dimensions, calculate multiple metrics
    withRevenue
      .groupBy("region", "category")
      .agg(
        sum("revenue").alias("total_revenue"),
        avg("price").alias("avg_price"),
        sum("quantity").alias("total_quantity"),
        count("*").alias("transaction_count")
      )
      .orderBy("region", "category")
  }

  /**
   * Demonstrate null handling strategies.
   *
   * Production data always has missing values.
   * This shows standard techniques for data cleaning.
   *
   * @param spark Active SparkSession
   * @return DataFrame with nulls handled
   */
  def handleNulls(spark: SparkSession): DataFrame = {
    val salesDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/sales.csv").getPath)

    // Strategy 1: Fill nulls with defaults
    val filled = salesDF.na.fill(Map(
      "quantity" -> 0,
      "price" -> 0.0,
      "region" -> "Unknown"
    ))

    // Strategy 2: Use coalesce for column-level defaults
    filled
      .withColumn("safe_quantity", coalesce(col("quantity"), lit(0)))
      .withColumn("safe_price", coalesce(col("price"), lit(0.0)))
  }

  /**
   * Add conditional derived columns.
   *
   * Categorization and bucketing based on business rules.
   * Type-safe alternative to SQL CASE statements.
   *
   * @param spark Active SparkSession
   * @return DataFrame with price tier column
   */
  def addConditionalColumns(spark: SparkSession): DataFrame = {
    val salesDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/sales.csv").getPath)

    salesDF
      .withColumn(
        "price_tier",
        when(col("price") > 1000, "premium")
          .when(col("price") > 500, "standard")
          .otherwise("budget")
      )
      .withColumn("high_volume", col("quantity") > 5)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("AdvancedDataFrames")
      .master("local[*]")
      .getOrCreate()

    println("Sales data loaded from resources/sales.csv")
    println()

    // Demonstrate pivot table
    println("Pivot table - Revenue by Region and Product:")
    val pivotDF = createPivotTable(spark)
    pivotDF.show(false)
    println()

    // Demonstrate window functions
    println("Running totals by region:")
    val runningDF = calculateRunningTotals(spark)
    runningDF.orderBy("region", "date").show(false)
    println()

    // Demonstrate multi-level aggregation
    println("Multi-level aggregation (Region × Category):")
    val aggDF = multiLevelAggregation(spark)
    aggDF.show(false)
    println()

    // Demonstrate conditional columns
    println("Conditional columns (price tiers):")
    val conditionalDF = addConditionalColumns(spark)
    conditionalDF.select("product", "price", "price_tier", "quantity", "high_volume").show(false)

    spark.stop()
  }
}
