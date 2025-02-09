import unittest
from pyspark.sql import SparkSession

class PySparkTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.spark = SparkSession.builder.appName("PySparkTest").master("local").getOrCreate()

    @classmethod
    def tearDownClass(cls):
        cls.spark.stop()

    def test_count_lines(self):
        # Create a dummy test file
        test_file = "test_file.txt"
        test_content = """
            This line contains a.
            This line contains b.
            This line contains both a and b.
            This line contains neither.
        """
        with open(test_file, "w") as f:
            f.write(test_content)

        logData = self.spark.read.text(test_file).cache()

        numAs = logData.filter(logData.value.contains('a')).count()
        numBs = logData.filter(logData.value.contains('b')).count()

        self.assertEqual(numAs, 2, "Incorrect count of lines with 'a'")
        self.assertEqual(numBs, 2, "Incorrect count of lines with 'b'")

        print(f"Lines with a: {numAs}, lines with b: {numBs}")  # Print during test

        # Clean up the test file
        import os
        os.remove(test_file)

    def test_empty_file(self):
        test_file = "empty_test_file.txt"
        with open(test_file, "w") as f:
            f.write("")  # Create empty file

        logData = self.spark.read.text(test_file).cache()
        numAs = logData.filter(logData.value.contains('a')).count()
        numBs = logData.filter(logData.value.contains('b')).count()

        self.assertEqual(numAs, 0, "Should be 0 for empty file")
        self.assertEqual(numBs, 0, "Should be 0 for empty file")

        import os
        os.remove(test_file)


if __name__ == '__main__':
    unittest.main()