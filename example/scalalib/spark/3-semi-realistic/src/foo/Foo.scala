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

    // Check for a file path provided as a command-line argument first;
    // otherwise, use resources.
    val resourcePath: String = args.headOption
      .orElse(Option(getClass.getResource("/transactions.csv")).map(_.getPath))
      .getOrElse(throw RuntimeException(
        "transactions.csv not provided as argument and not found in resources"
      ))

    import spark.implicits._

    val df = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(resourcePath)

    val transactionsDS: Dataset[Transaction] = df.as[Transaction]
    val summaryDF = computeSummary(transactionsDS)

    println("Summary Statistics by Category:")
    summaryDF.show()
    spark.stop()
  }
}
