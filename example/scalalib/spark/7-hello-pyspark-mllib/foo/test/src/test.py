import unittest
from pyspark.sql import SparkSession
from foo import tokenize

class TokenizeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spark = SparkSession.builder \
            .appName("MLlibTest") \
            .master("local[*]") \
            .getOrCreate()

    @classmethod
    def tearDownClass(cls):
        cls.spark.stop()

    def test_tokenize(self):
        df = tokenize(self.spark)
        tokens = [row["words"] for row in df.collect()]
        self.assertEqual(tokens, [["hello", "mllib"]])

if __name__ == "__main__":
    unittest.main()