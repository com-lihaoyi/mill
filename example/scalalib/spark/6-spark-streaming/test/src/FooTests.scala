package foo

import utest._
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.Encoders

object FooTests extends TestSuite {
  val spark: SparkSession = SparkSession.builder()
    .appName("Spark Streaming Tests")
    .master("local[*]")
    .getOrCreate()

  import spark.implicits._ // Import Spark implicits for Dataset operations

  spark.sparkContext.setLogLevel("ERROR")

  override def utestAfterAll(): Unit = {
    spark.stop()
  }

  val tests = Tests {
    test("createStreamingDF should return a streaming DataFrame") {
      val df = Foo.createStreamingDF(spark)
      assert(df.isStreaming)
    }

    test("getFileName should extract the correct file name") {
      val testPath = getClass.getResource("/data/GOOGL_stock_data.csv").getPath
      val expected = "GOOGL"
      val result = Foo.getFileName
      val df = spark.read.schema(Foo.schema).csv(testPath).withColumn("Name", result)
      val actual = df.select("Name").as[String].head()
      assert(actual == expected)
    }

    test("append mode should output raw data") {
      val df = Foo.createStreamingDF(spark)
      val query = df.writeStream
        .outputMode("append")
        .format("memory")
        .queryName("appendOutput")
        .trigger(Trigger.Once())
        .start()
      query.awaitTermination()

      val result = spark.sql("SELECT * FROM appendOutput")
      assert(result.count() > 0)
    }

    test("update mode should output aggregated data") {
      val df = Foo.createStreamingDF(spark)
      df.createOrReplaceTempView("updateView")
      val aggregated = spark.sql(
        """SELECT Year(Date) AS Year, Name, MAX(High) AS Max 
          |FROM updateView 
          |GROUP BY Name, Year""".stripMargin
      )

      val query = aggregated.writeStream
        .outputMode("update")
        .format("memory")
        .queryName("updateOutput")
        .trigger(Trigger.Once())
        .start()
      query.awaitTermination()

      val result = spark.sql("SELECT * FROM updateOutput")
      assert(result.count() > 0)
    }

    test("complete mode should output aggregated data") {
      val df = Foo.createStreamingDF(spark)
      df.createOrReplaceTempView("completeView")
      val aggregated = spark.sql(
        """SELECT Year(Date) AS Year, Name, MAX(High) AS Max 
          |FROM completeView 
          |GROUP BY Name, Year""".stripMargin
      )

      val query = aggregated.writeStream
        .outputMode("complete")
        .format("memory")
        .queryName("completeOutput")
        .trigger(Trigger.Once())
        .start()
      query.awaitTermination()

      val result = spark.sql("SELECT * FROM completeOutput")
      assert(result.count() > 0)
    }
  }
}
