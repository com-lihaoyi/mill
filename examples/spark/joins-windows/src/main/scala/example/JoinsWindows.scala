package example

import org.apache.spark.sql.{SparkSession, functions => F}
import org.apache.spark.sql.expressions.Window

/**
 * Demonstrates joining two tiny DataFrames and computing a running total per
 * customer using a window aggregate. The results are ordered by id and
 * amount. This example is intended to be run via Mill's spark tasks.
 */
object JoinsWindows {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("JoinsWindows").getOrCreate()
    import spark.implicits._

    val customers = Seq((1, "A"), (2, "B")).toDF("id", "name")
    val orders = Seq((1, 100.0), (1, 50.0), (2, 25.0)).toDF("cid", "amount")
    val joined = customers.join(orders, customers("id") === orders("cid"))
    val w = Window.partitionBy("id")
    val out = joined
      .withColumn("total", F.sum("amount").over(w))
      .select("id", "name", "amount", "total")
      .orderBy("id", "amount")

    out.show(false)
    spark.stop()
  }
}