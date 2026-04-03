package foo

import org.apache.spark.sql.{SparkSession, Dataset, DataFrame}
import org.apache.spark.sql.functions.*

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

    val resourcePath: String = args(0)

    import spark.implicits.*

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

object dummy2
