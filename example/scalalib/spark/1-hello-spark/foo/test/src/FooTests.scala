package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("helloWorld should create a DataFrame with one row containing 'Hello, World!'") {
      // Create a SparkSession for testing
      // Using local mode means tests run fast without cluster setup
      val spark = SparkSession.builder()
        .appName("HelloWorldTest")
        .master("local[*]") // Use all cores for parallel test execution
        .getOrCreate() // Important: reuse session if one exists

      // Test the helloWorld function
      val df = Foo.helloWorld(spark)

      // Collect results to driver for assertion
      // Note: collect() brings all data to driver - fine for tests, dangerous in production
      val messages = df.collect().map(_.getString(0)).toList
      assert(messages == List("Hello, World!"))

      // Clean up: stop the SparkSession to release resources
      // Critical in test suites to prevent resource leaks across tests
      spark.stop()
    }
  }
}

// ### Testing Spark Applications
//
// This demonstrates Spark's powerful testing story: the same code that runs on clusters
// can be tested locally with instant feedback. Key testing patterns:
//
// **Local Mode Testing**
// - Tests run in milliseconds using local[*] mode
// - No cluster setup required
// - Same code paths as production
//
// **collect() for Assertions**
// - Brings distributed data to driver for verification
// - Safe in tests (small data)
// - Avoid in production (can cause OutOfMemoryError with large datasets)
//
// **SparkSession Lifecycle**
// - Create session per test for isolation
// - Always call .stop() to prevent resource leaks
// - Use .getOrCreate() to handle session reuse
//
// **Best Practices**
// - Keep test data small (< 1000 rows)
// - Test logic, not Spark internals
// - Use local mode for unit tests
// - Save integration tests for cluster deployment
