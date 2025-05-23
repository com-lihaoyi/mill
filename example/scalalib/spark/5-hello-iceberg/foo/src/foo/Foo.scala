package foo

import org.apache.spark.sql.{DataFrame, SparkSession}
import java.nio.file.{Files, Paths}

object Foo {

  def writeIcebergTable(spark: SparkSession, tableName: String): Unit = {
    import spark.implicits._
    val data = Seq("Hello, Iceberg!").toDF("message")
    data.writeTo(tableName).using("iceberg").createOrReplace()
  }

  def readIcebergTable(spark: SparkSession, tableName: String): DataFrame = {
    spark.read.table(tableName)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("HelloIceberg")
      .master("local[*]")
      .config("spark.sql.catalog.local", "org.apache.iceberg.spark.SparkCatalog")
      .config("spark.sql.catalog.local.type", "hadoop")
      .config("spark.sql.catalog.local.warehouse", "tmp/iceberg-warehouse")
      .getOrCreate()

    val table = "local.db.hello_iceberg"

    writeIcebergTable(spark, table)
    readIcebergTable(spark, table).show()

    spark.stop()
  }
}
