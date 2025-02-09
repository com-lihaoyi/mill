package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .master("local[*]")
    .appName("Hello-World-Test")
    .config("spark.ui.port", "4050")
    .getOrCreate()

  def tests = Tests {
    test("TEST: 'Hello, Spark!'") {
      val df = Foo.helloSpark(spark)
      val collectedData = df.collect().map(_.getString(0))
      assert(collectedData.contains("Hello, Spark!"))
    }
  }

  // Ensure that Spark is stopped after tests complete
  sys.addShutdownHook {
    spark.stop()
  }
}
