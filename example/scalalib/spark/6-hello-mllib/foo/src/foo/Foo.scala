package foo

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.ml.feature.Tokenizer

object Foo {

  def tokenize(spark: SparkSession): DataFrame = {
    import spark.implicits._
    val data = Seq("Hello MLlib").toDF("text")
    val tokenizer = new Tokenizer().setInputCol("text").setOutputCol("words")
    val result = tokenizer.transform(data)
    result.select("words")
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("MLlibExample")
      .master("local[*]")
      .getOrCreate()

    tokenize(spark).show()

    spark.stop()
  }
}

