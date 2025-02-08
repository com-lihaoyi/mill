package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("helloWorld should create a DataFrame with one row containing 'Hello, World!'") {
      val spark = SparkSession.builder()
        .appName("HelloWorldTest")
        .master("local[*]")
        .getOrCreate()

      val df = Foo.helloWorld(spark)
      val messages = df.collect().map(_.getString(0)).toList
      assert(messages == List("Hello, World!"))

      // Stop the SparkSession
      spark.stop()
    }
  }
}
