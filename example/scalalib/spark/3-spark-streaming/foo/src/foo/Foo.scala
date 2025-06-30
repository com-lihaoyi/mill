package foo

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object Foo {

  def streamFromKafka(spark: SparkSession): DataFrame = {
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", sys.env("KAFKA_SERVER"))
      .option("subscribe", "test-topic")
      .option("startingOffsets", "earliest")
      .load()
      .selectExpr("CAST(value AS STRING)").toDF("message")
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("HelloWorldKafka")
      .master("local[*]")
      .getOrCreate()

    val df = streamFromKafka(spark)

    val query = df.writeStream
      .format("console")
      .outputMode("append")
      .start()

    query.awaitTermination(9000)
  }
}
