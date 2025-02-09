import unittest
from pyspark.sql import SparkSession
from foo import (
    create_spark_session,
    compute_average_age,
    compute_max_salary,
    compute_min_salary,
)


class TestScript(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        """Initialize a SparkSession for testing"""
        cls.spark = create_spark_session()

    @classmethod
    def tearDownClass(cls):
        """Stop SparkSession after all tests"""
        cls.spark.stop()

    def setUp(self):
        """Create a test DataFrame"""
        data = [
            ("Alice", 25, 50000),
            ("Bob", 30, 60000),
            ("Charlie", 35, 75000),
            ("David", 40, 80000),
            ("Eve", 28, 70000),
        ]
        columns = ["Name", "Age", "Salary"]
        self.df = self.spark.createDataFrame(data, columns)

    def test_compute_average_age(self):
        """Test average age computation"""
        self.assertAlmostEqual(compute_average_age(self.df), 31.6, places=1)

    def test_compute_max_salary(self):
        """Test maximum salary computation"""
        self.assertEqual(compute_max_salary(self.df), 80000)

    def test_compute_min_salary(self):
        """Test minimum salary computation"""
        self.assertEqual(compute_min_salary(self.df), 50000)


if __name__ == "__main__":
    unittest.main()
