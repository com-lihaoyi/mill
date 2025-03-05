import unittest
from pyspark.sql import SparkSession
from foo import hello_world

class HelloWorldTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spark = SparkSession.builder \
            .appName("HelloWorldTest") \
            .master("local[*]") \
            .getOrCreate()

    @classmethod
    def tearDownClass(cls):
        cls.spark.stop()

    def test_hello_world(self):
        df = hello_world(self.spark)
        messages = [row['message'] for row in df.collect()]
        self.assertEqual(messages, ["Hello, World!"])

if __name__ == "__main__":
    unittest.main()