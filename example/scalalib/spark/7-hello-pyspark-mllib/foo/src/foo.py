import monkey_patch
from pyspark.sql import SparkSession, DataFrame
from pyspark.ml.feature import Tokenizer

def tokenize(spark: SparkSession) -> DataFrame:
    data = [("Hello MLlib",)]
    df = spark.createDataFrame(data, ["text"])
    tokenizer = Tokenizer(inputCol="text", outputCol="words")
    result = tokenizer.transform(df)
    return result.select("words")

def main():
    spark = SparkSession.builder \
        .appName("MLlibExample") \
        .master("local[*]") \
        .getOrCreate()

    tokenize(spark).show(truncate=False)

    spark.stop()

if __name__ == "__main__":
    main()