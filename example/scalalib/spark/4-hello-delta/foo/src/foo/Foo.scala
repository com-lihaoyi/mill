package foo

import org.apache.spark.sql.{DataFrame, SparkSession}
import io.delta.tables._

object Foo {

  def writeDeltaTable(spark: SparkSession, path: String): Unit = {
    import spark.implicits._
    val data = Seq("Hello, Delta!").toDF("message")
    data.write.format("delta").mode("overwrite").save(path)
  }

  def readDeltaTable(spark: SparkSession, path: String): DataFrame = {
    spark.read.format("delta").load(path)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("HelloDelta")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()

    val deltaPath = "tmp/delta-table"

    writeDeltaTable(spark, deltaPath)
    readDeltaTable(spark, deltaPath).show()

    spark.stop()
  }
}
