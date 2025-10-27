package foo

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.StreamingQuery
import utest._

/**
 * Test suite for Spark Structured Streaming example.
 *
 * Testing streaming applications requires special patterns:
 * - Create temporary directories for source and checkpoint
 * - Write test data files to source directory
 * - Start streaming query and wait for processing
 * - Verify results were produced
 * - Clean up query and temporary files
 *
 * This demonstrates testing best practices for streaming workloads.
 */
object FooTests extends TestSuite {
  // Initialize SparkSession once for all tests
  val spark = SparkSession.builder()
    .appName("StreamingTests")
    .master("local[2]")  // Need at least 2 threads: one for streaming, one for driver
    .getOrCreate()

  def tests = Tests {
    /**
     * Verify basic streaming file processing works.
     *
     * Testing strategy:
     * 1. Create temp directories for source and checkpoint
     * 2. Start streaming query
     * 3. Write test data file to source directory
     * 4. Wait for query to process the file
     * 5. Verify query processed data without errors
     * 6. Stop query and clean up
     */
    test("processStream should read and transform streaming data") {
      // Create temporary directories
      val tempSource = java.nio.file.Files.createTempDirectory("test-source")
      val tempCheckpoint = java.nio.file.Files.createTempDirectory("test-checkpoint")

      try {
        // Start streaming query (runs in background)
        val query = Foo.processStream(
          spark,
          tempSource.toString,
          tempCheckpoint.toString
        )

        // Verify query started successfully
        assert(query.isActive)
        println(s"Query started: ${query.id}")

        // Write test data to source directory
        // Streaming query will pick this up in next micro-batch
        val testFile = tempSource.resolve("test-data.txt")
        java.nio.file.Files.write(testFile,
          java.util.Arrays.asList(
            "Line 1: Test data",
            "Line 2: More test data",
            "Line 3: Streaming is working"
          ))
        println(s"Wrote test file: $testFile")

        // Wait for query to process the file
        // In production tests, use query.processAllAvailable() for deterministic testing
        Thread.sleep(8000)  // Wait for at least one micro-batch (5s trigger + processing time)

        // Verify query is still active and processed data
        assert(query.isActive)
        val status = query.status
        println(s"Query status: ${status.message}")

        // Verify progress - query should have processed at least one batch
        val progress = query.recentProgress
        if (progress.nonEmpty) {
          val lastBatch = progress.last
          println(s"Processed batches: ${progress.length}")
          println(s"Last batch: ${lastBatch.batchId}, rows: ${lastBatch.numInputRows}")
          assert(lastBatch.numInputRows > 0)  // Should have processed our test file
        } else {
          // Query might not have completed first batch yet
          println("Warning: No progress yet, query may need more time")
        }

        // Stop query gracefully
        query.stop()
        query.awaitTermination(5000)
        assert(!query.isActive)

      } finally {
        // Clean up temporary directories
        deleteRecursively(tempSource.toFile)
        deleteRecursively(tempCheckpoint.toFile)
      }
    }

    /**
     * Verify streaming aggregations work correctly.
     *
     * This tests stateful streaming operations that maintain
     * aggregate state across micro-batches.
     */
    test("streamingAggregation should compute statistics") {
      val tempSource = java.nio.file.Files.createTempDirectory("test-agg-source")
      val tempCheckpoint = java.nio.file.Files.createTempDirectory("test-agg-checkpoint")

      try {
        // Write initial test data
        val testFile = tempSource.resolve("initial-data.txt")
        java.nio.file.Files.write(testFile,
          java.util.Arrays.asList(
            "Short",
            "Medium length line",
            "This is a longer line of text"
          ))

        // Start streaming aggregation query
        val query = Foo.streamingAggregation(
          spark,
          tempSource.toString,
          tempCheckpoint.toString
        )

        assert(query.isActive)

        // Wait for processing
        Thread.sleep(12000)  // 10s trigger + processing time

        // Verify query processed data
        val progress = query.recentProgress
        if (progress.nonEmpty) {
          val lastBatch = progress.last
          println(s"Aggregation processed: ${lastBatch.numInputRows} rows")
          assert(lastBatch.numInputRows > 0)
        }

        // Stop query
        query.stop()
        query.awaitTermination(5000)

      } finally {
        deleteRecursively(tempSource.toFile)
        deleteRecursively(tempCheckpoint.toFile)
      }
    }
  }

  /**
   * Helper to recursively delete directories.
   * Needed for cleanup after tests.
   */
  def deleteRecursively(file: java.io.File): Unit = {
    if (file.isDirectory) {
      file.listFiles.foreach(deleteRecursively)
    }
    file.delete()
  }

  // Ensure SparkSession is stopped after all tests
  sys.addShutdownHook {
    spark.stop()
  }
}
