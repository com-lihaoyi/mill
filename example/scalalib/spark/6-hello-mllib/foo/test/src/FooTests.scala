package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("'tokenize' should split 'Hello MLlib' into words") {
      val spark = SparkSession.builder()
        .appName("MLlibTest")
        .master("local[*]")
        .getOrCreate()

      val df = Foo.tokenize(spark)
      val tokens = df.collect().toList.flatMap(_.getSeq(0))
      assert(tokens == List("hello", "mllib"))

      spark.stop()
    }
  }
}
