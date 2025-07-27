package example

import org.apache.spark.sql.{SparkSession, functions => F}

object UdfVsBuiltins {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("UDFvsBuiltins").getOrCreate()
    import spark.implicits._
    val df = Seq("a", "bb", "ccc").toDF("s")
    // Define a simple UDF
    val lenUdf = F.udf((s: String) => s.length)
    val withUdf = df.withColumn("len_udf", lenUdf(F.col("s")))
    // Use built-in length function
    val withBuiltin = withUdf.withColumn("len_builtin", F.length(F.col("s")))
    withBuiltin.show(false)
    spark.stop()
  }
}
