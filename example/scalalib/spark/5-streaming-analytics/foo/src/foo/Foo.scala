package foo

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import play.api.libs.json.{Json, JsValue}

object Foo {

  case class StockTick(symbol: String, price: Double, timestamp: Long)

  def generateMockStockData(spark: SparkSession): DataFrame = {
    import spark.implicits._

    val stocks = Seq(
      StockTick("AAPL", 198.50, System.currentTimeMillis()),
      StockTick("MSFT", 425.22, System.currentTimeMillis()),
      StockTick("GOOG", 175.45, System.currentTimeMillis()),
      StockTick("AAPL", 203.07, System.currentTimeMillis() + 1000),
      StockTick("MSFT", 423.10, System.currentTimeMillis() + 1000),
      StockTick("GOOG", 178.63, System.currentTimeMillis() + 1000)
    ).toDF()

    stocks
  }

  def calculatePriceMovements(df: DataFrame): DataFrame = {
    val windowSpec = org.apache.spark.sql.expressions.Window
      .partitionBy("symbol")
      .orderBy("timestamp")

    val withPrevious = df.withColumn("prevPrice", lag("price", 1).over(windowSpec))

    val withMovement = withPrevious
      .filter(col("prevPrice").isNotNull)
      .withColumn("movement", (col("price") - col("prevPrice")) / col("prevPrice") * 100)
      .withColumn("direction", when(col("movement") >= 0, "?").otherwise("?"))
      .withColumn(
        "priceMovement",
        concat(col("direction"), lit(" "), format_number(abs(col("movement")), 1), lit("%"))
      )
      .select("symbol", "priceMovement")

    withMovement
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("StockAnalytics")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    // Generate mock streaming data
    val stockData = generateMockStockData(spark)

    // Process the data
    val results = calculatePriceMovements(stockData)

    // Show the results
    results.show(false)

    spark.stop()
  }
}
