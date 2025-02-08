package foo

import org.apache.spark.sql.{SparkSession, Dataset, DataFrame}
import org.apache.spark.sql.functions._

object Foo {

  case class Transaction(id: Int, category: String, amount: Double)

  def computeSummary(transactions: Dataset[Transaction]): DataFrame = {
    transactions.groupBy("category")
      .agg(
        sum("amount").alias("total_amount"),
        avg("amount").alias("average_amount"),
        count("amount").alias("transaction_count")
      )
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("SparkExample")
      .master("local[*]")
      .getOrCreate()

    val resourceUrl = Option(getClass.getResource("/transactions.csv"))
      .getOrElse(throw new RuntimeException("transactions.csv not found in resources"))
    val resourcePath = resourceUrl.getPath

    import spark.implicits._

    val df = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(resourcePath)

    val transactionsDS: Dataset[Transaction] = df.as[Transaction]
    val summaryDF = computeSummary(transactionsDS)    // Compute summary statistics using the exported function

    println("Summary Statistics by Category:")
    summaryDF.show()
    spark.stop() // End Session
  }
}
