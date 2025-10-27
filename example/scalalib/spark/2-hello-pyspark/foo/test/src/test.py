"""
Unit tests for PySpark hello_world example.

This demonstrates testing best practices for Spark applications:
- Reuse single SparkSession across all tests (faster, avoids initialization overhead)
- Use local mode for fast, deterministic testing
- collect() to bring data to driver for assertions (safe for small test data)
"""
import unittest
from pyspark.sql import SparkSession
from foo import hello_world

class HelloWorldTest(unittest.TestCase):
    """
    Test suite for hello_world DataFrame creation.

    Uses unittest's class-level setup/teardown to manage SparkSession lifecycle.
    This pattern is standard for PySpark testing - initialize once, test multiple times.
    """

    @classmethod
    def setUpClass(cls):
        """
        Initialize SparkSession once before all tests in this class.

        This is more efficient than creating a new session for each test.
        The same session can be safely reused across multiple test methods.
        """
        cls.spark = SparkSession.builder \
            .appName("HelloWorldTest") \
            .master("local[*]") \
            .getOrCreate()
        # local[*] ensures tests run locally without external cluster dependencies

    @classmethod
    def tearDownClass(cls):
        """
        Clean up SparkSession after all tests complete.

        Critical for preventing resource leaks in test suites.
        """
        cls.spark.stop()

    def test_hello_world(self):
        """
        Verify hello_world returns DataFrame with expected message.

        Testing pattern:
        1. Call function to get DataFrame (lazy evaluation)
        2. .collect() brings all data to driver (ACTION that executes query)
        3. Extract values and assert expectations

        Note: collect() is safe here because we know the data is tiny.
        In production tests with larger data, use .take(n) or .first() instead.
        """
        # Get DataFrame from function (no computation yet - lazy evaluation)
        df = hello_world(self.spark)

        # collect() forces execution and returns list of Row objects
        # Extract 'message' field from each Row
        messages = [row['message'] for row in df.collect()]

        # Verify the expected message
        self.assertEqual(messages, ["Hello, World!"])

if __name__ == "__main__":
    # Run tests when script executed directly
    unittest.main()