package foo

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Column
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming.Trigger

object Foo {
  // Define schema
  val schema: StructType = StructType(List(
    StructField("Date", StringType, true),
    StructField("Open", DoubleType, true),
    StructField("High", DoubleType, true),
    StructField("Low", DoubleType, true),
    StructField("Close", DoubleType, true),
    StructField("Adjusted Close", DoubleType, true),
    StructField("Volume", DoubleType, true)
  ))

  // Function to extract file name
  def getFileName: Column = {
    val file_name = reverse(split(input_file_name(), "/")).getItem(0)
    split(file_name, "_").getItem(0)
  }

  // Function to create streaming DataFrame
  def createStreamingDF(spark: SparkSession): DataFrame = {
    spark.readStream
      .format("csv")
      .option("maxFilesPerTrigger", 2)
      .option("header", true)
      .schema(schema)
      .load(spark.getClass.getResource("/data").getPath)
      .withColumn("Name", getFileName)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Spark Streaming")
      .master("local[*]")
      .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")

    // Check if DataFrame is streaming
    println("Is this Streaming DataFrame : " + createStreamingDF(spark).isStreaming)

    // Print Schema
    println("Schema of DataFrame")
    createStreamingDF(spark).printSchema()

    // Append Mode
    val appendDF = createStreamingDF(spark)
    val appendQuery = appendDF.writeStream
      .outputMode("append")
      .format("console")
      .option("truncate", false)
      .option("numRows", 3)
      .trigger(Trigger.Once())
      .start()
    appendQuery.awaitTermination()

    // Update Mode
    val updateDF = createStreamingDF(spark)
    updateDF.createOrReplaceTempView("updateView")
    val updateResult = spark.sql(
      """SELECT Year(Date) AS Year, Name, MAX(High) AS Max 
        |FROM updateView 
        |GROUP BY Name, Year""".stripMargin
    )

    val updateQuery = updateResult.writeStream
      .outputMode("update")
      .format("console")
      .option("truncate", false)
      .option("numRows", 3)
      .trigger(Trigger.Once())
      .start()
    updateQuery.awaitTermination()

    // Complete Mode
    val completeDF = createStreamingDF(spark)
    completeDF.createOrReplaceTempView("completeView")
    val completeResult = spark.sql(
      """SELECT Year(Date) AS Year, Name, MAX(High) AS Max 
        |FROM completeView 
        |GROUP BY Name, Year""".stripMargin
    )

    val completeQuery = completeResult.writeStream
      .outputMode("complete")
      .format("console")
      .option("truncate", false)
      .option("numRows", 3)
      .trigger(Trigger.Once())
      .start()
    completeQuery.awaitTermination()

    spark.stop()
  }
}
