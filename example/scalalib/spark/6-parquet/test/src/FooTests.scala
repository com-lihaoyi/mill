package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .appName("ParquetTests")
    .master("local[*]")
    .getOrCreate()

  def tests = Tests {
    test("parquetReadWrite should preserve data accurately") {
      import spark.implicits._

      // Create test data
      val testData = Seq(
        (1, "Alice", 28, 75000),
        (2, "Bob", 35, 85000),
        (3, "Carol", 42, 95000)
      ).toDF("id", "name", "age", "salary")

      val tempPath = java.nio.file.Files.createTempDirectory("test-parquet").toString

      // Write as Parquet
      testData.write.parquet(tempPath)

      // Read back
      val readData = spark.read.parquet(tempPath)

      // Verify schema preserved
      assert(readData.schema == testData.schema)

      // Verify data preserved
      val results = readData.collect().sortBy(_.getAs[Int]("id"))
      assert(results.length == 3)
      assert(results(0).getAs[String]("name") == "Alice")
      assert(results(1).getAs[Int]("age") == 35)
      assert(results(2).getAs[Int]("salary") == 95000)
    }
  }

  sys.addShutdownHook {
    spark.stop()
  }
}
