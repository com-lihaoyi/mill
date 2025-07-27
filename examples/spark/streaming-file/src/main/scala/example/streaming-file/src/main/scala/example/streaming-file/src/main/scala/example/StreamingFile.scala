package example

import org.apache.spark.sql.{SparkSession, functions => F}
import org.apache.spark.sql.types.StructType

object StreamingFile {
  def main(args: Array[String]): Unit = {
    // Directory to monitor for new CSV files, default to "inbox"
    val inputDir = if (args.nonEmpty) args(0) else "inbox"
    val spark = SparkSession.builder().appName("StreamingFile").getOrCreate()
    import spark.implicits._
    val schema = new StructType().add("name", "string").add("value", "int")
    val stream = spark.readStream.schema(schema).csv(inputDir)
    val query = stream.writeStream.format("console").outputMode("append").start()
    // Wait a short time before stopping to demonstrate streaming
    query.awaitTermination(5000)
    spark.stop()
  }
}
