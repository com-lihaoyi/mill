from pyspark.sql import SparkSession, DataFrame

def hello_world(spark: SparkSession) -> DataFrame:
    data = [("Hello, World!",)]
    df = spark.createDataFrame(data, ["message"])
    return df

def main():
    spark = SparkSession.builder \
        .appName("HelloWorld") \
        .master("local[*]") \
        .getOrCreate()

    hello_world(spark).show()

    spark.stop()

if __name__ == "__main__":
    main()