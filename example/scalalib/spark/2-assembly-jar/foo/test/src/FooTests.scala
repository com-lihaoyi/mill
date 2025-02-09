package foo

import utest._
import org.apache.spark.sql.{SparkSession, DataFrame}

object FooTests extends TestSuite {

  val spark: SparkSession = SparkSession.builder()
    .appName("Assembly-Jar-Test")
    .master("local[*]")
    .config("spark.ui.port", "4051")
    .getOrCreate()

  import spark.implicits._

  val tests = Tests {
    test("TEST: Word Count") {
      val inputDf: DataFrame = Seq("hello world hello spark spark").toDF("line")
      val wordCountDf = Foo.countWords(inputDf)

      val result = wordCountDf.collect().map(row => row.getString(0) -> row.getLong(1)).toMap
      val expected = Map("hello" -> 2, "world" -> 1, "spark" -> 2)

      assert(result == expected)
    }
  }

  // Ensure that Spark is stopped after tests complete
  sys.addShutdownHook {
    spark.stop()
  }

}
