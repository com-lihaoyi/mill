import org.apache.spark.sql.SparkSession
import utest._

object FooTest extends TestSuite {

  val tests = Tests {
    test("Count lines with 'a' and 'b'") {
      val spark = SparkSession.builder.appName("FooTest").master("local").getOrCreate()

      try {
        val testFile = "test_file.txt"
        val testContent =
          """
            This line contains a.
            This line contains b.
            This line contains both a and b.
            This line contains neither.
          """
        scala.tools.nsc.io.File(testFile).writeAll(testContent)

        val logData = spark.read.textFile(testFile).cache()

        val numAs = logData.filter(line => line.contains("a")).count()
        val numBs = logData.filter(line => line.contains("b")).count()

        assert(numAs == 2)
        assert(numBs == 2)

        println(s"Lines with a: $numAs, Lines with b: $numBs")

        scala.tools.nsc.io.File(testFile).delete()

      } finally {
        spark.stop()
      }
    }

    test("Empty file test") {
      val spark = SparkSession.builder.appName("FooTestEmpty").master("local").getOrCreate()

      try {
        val testFile = "empty_test_file.txt"
        scala.tools.nsc.io.File(testFile).writeAll("")

        val logData = spark.read.textFile(testFile).cache()
        val numAs = logData.filter(line => line.contains("a")).count()
        val numBs = logData.filter(line => line.contains("b")).count()

        assert(numAs == 0)
        assert(numBs == 0)

        scala.tools.nsc.io.File(testFile).delete()

      } finally {
        spark.stop()
      }
    }
  }
}