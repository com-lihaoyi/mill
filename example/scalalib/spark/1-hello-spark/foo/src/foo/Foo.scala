package foo

import org.apache.spark.sql.{DataFrame, SparkSession}

object Foo {

  def helloWorld(spark: SparkSession): DataFrame = {
    val data = Seq("Hello, World!")
    val df = spark.createDataFrame(data.map(Tuple1(_))).toDF("message")
    df
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("HelloWorld")
      .master("local[*]")
      .getOrCreate()

    helloWorld(spark).show()

    spark.stop()
  }
}
