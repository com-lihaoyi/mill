package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("Delta table should contain one row with 'Hello, Delta!'") {
      val spark = SparkSession.builder()
        .appName("FooDeltaTest")
        .master("local[*]")
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config(
          "spark.sql.catalog.spark_catalog",
          "org.apache.spark.sql.delta.catalog.DeltaCatalog"
        )
        .getOrCreate()

      val path = "tmp/test-delta-table"
      Foo.writeDeltaTable(spark, path)
      val df = Foo.readDeltaTable(spark, path)

      val messages = df.collect().map(_.getString(0)).toList
      assert(messages == List("Hello, Delta!"))

      spark.stop()
    }
  }
}
