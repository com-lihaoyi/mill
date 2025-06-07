package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("Iceberg table should contain one row with 'Hello, Iceberg!'") {
      val spark = SparkSession.builder()
        .appName("FooTests")
        .master("local[*]")
        .config("spark.sql.catalog.local", "org.apache.iceberg.spark.SparkCatalog")
        .config("spark.sql.catalog.local.type", "hadoop")
        .config("spark.sql.catalog.local.warehouse", "tmp/test-iceberg-warehouse")
        .getOrCreate()

      val table = "local.db.hello_iceberg_test"
      Foo.writeIcebergTable(spark, table)
      val df = Foo.readIcebergTable(spark, table)

      val messages = df.collect().map(_.getString(0)).toList
      assert(messages == List("Hello, Iceberg!"))

      spark.stop()
    }
  }
}
