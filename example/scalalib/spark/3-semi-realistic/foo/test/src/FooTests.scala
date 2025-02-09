package foo

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import utest._

object FooTest extends TestSuite {
  // Initialize SparkSession for testing
  implicit val spark: SparkSession = SparkSession.builder()
    .appName("Semi-Realistic-Test")
    .master("local[*]")
    .config("spark.ui.port", "4052")
    .getOrCreate()

  import spark.implicits._

  // Test data
  val testData: DataFrame = Seq(
    ("2025-02-09 10:15:30", "ERROR", "/api/user", "User not found"),
    ("2025-02-09 11:20:45", "INFO", "/api/home", "Page loaded"),
    ("2025-02-09 10:35:10", "ERROR", "/api/login", "Invalid credentials"),
    ("2025-02-09 12:50:15", "ERROR", "/api/user", "User not found"),
    ("2025-02-09 10:05:05", "ERROR", "/api/user", "Database timeout")
  ).toDF("timestamp", "level", "endpoint", "message")

  // Convert test data to Dataset[LogRecord]
  val logRecordsDS: Dataset[LogRecord] = testData.as[LogRecord]

  // Clean the test data
  val cleanedDF: DataFrame = Foo.cleanData(logRecordsDS)

  // Perform analyses
  val errorsPerHour: DataFrame = Foo.computeErrorsPerHour(cleanedDF)
  val commonErrors: DataFrame = Foo.computeCommonErrors(cleanedDF)
  val errorProneEndpoints: DataFrame = Foo.computeErrorProneEndpoints(cleanedDF)

  // Define tests
  val tests: Tests = Tests {
    test("Errors per hour should be counted correctly") {
      val errorCounts = errorsPerHour.collect()
        .map(row => (row.getInt(0), row.getLong(1)))
        .toMap
      assert(errorCounts == Map(10 -> 3, 12 -> 1))
    }

    test("Most common error messages should be identified") {
      val topErrors = commonErrors.collect()
        .map(row => (row.getString(0), row.getLong(1)))
        .toMap
      assert(topErrors("User not found") == 2)
      assert(topErrors("Invalid credentials") == 1)
      assert(topErrors("Database timeout") == 1)
    }

    test("Most error-prone endpoints should be identified") {
      val topEndpoints = errorProneEndpoints.collect()
        .map(row => (row.getString(0), row.getLong(1)))
        .toMap
      assert(topEndpoints("/api/user") == 3)
      assert(topEndpoints("/api/login") == 1)
    }
  }

  // Ensure that Spark is stopped after tests complete
  sys.addShutdownHook {
    spark.stop()
  }
}
