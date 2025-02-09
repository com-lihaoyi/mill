from pyspark.sql import SparkSession
from pyspark.sql.functions import avg, max, min
import importlib.resources


def create_spark_session():
    """Initialize and return a SparkSession."""
    return (
        SparkSession.builder.appName("4-PySpark")
        .config("spark.ui.port", "4053")
        .getOrCreate()
    )


def load_data(spark, file_path):
    """Load CSV data into a DataFrame."""
    return (
        spark.read.option("header", "true").option("inferSchema", "true").csv(file_path)
    )


def compute_average_age(df):
    """Compute and return the average age."""
    return df.select(avg("Age")).collect()[0][0]


def compute_max_salary(df):
    """Compute and return the maximum salary."""
    return df.select(max("Salary")).collect()[0][0]


def compute_min_salary(df):
    """Compute and return the minimum salary."""
    return df.select(min("Salary")).collect()[0][0]


if __name__ == "__main__":
    spark = create_spark_session()

    # Read CSV File
    input_path = str(importlib.resources.files("res").joinpath("data.csv"))
    df = load_data(spark, input_path)

    # Show the DataFrame
    df.show()

    # Compute and print results
    print(f"Average Age: {compute_average_age(df):.2f}")
    print(f"Maximum Salary: ${compute_max_salary(df)}")
    print(f"Minimum Salary: ${compute_min_salary(df)}")

    spark.stop()
