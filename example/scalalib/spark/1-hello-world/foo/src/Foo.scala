package foo

import org.apache.spark.sql.{SparkSession, DataFrame}

object Foo {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("HelloSpark")
      .config("spark.ui.port", "4050")
      .getOrCreate()

    val df = helloSpark(spark)
    df.show()

    spark.stop()
  }

  def helloSpark(spark: SparkSession): DataFrame = {
    import spark.implicits._
    Seq(("Hello, Spark!")).toDF("Mill Build Tool")
  }
}
