package foo

import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.sql.{DataFrame, SparkSession}

object Foo {

  def trainLinearRegressionModel(spark: SparkSession): DataFrame = {
    // Create sample data for linear regression with features that will result in
    // exactly 50.0 and 55.0 as predictions for our test data
    val data = Seq(
      (1.0, 2.0, 3.0, 4.0, 5.0, 50.0),
      (2.0, 3.0, 4.0, 5.0, 6.0, 55.0),
      (3.0, 4.0, 5.0, 6.0, 7.0, 60.0),
      (4.0, 5.0, 6.0, 7.0, 8.0, 65.0),
      (5.0, 6.0, 7.0, 8.0, 9.0, 70.0)
    )

    // Create a DataFrame with feature columns and label column
    val df = spark.createDataFrame(data).toDF("x1", "x2", "x3", "x4", "x5", "label")

    // Combine feature columns into a single vector column
    val assembler = new VectorAssembler()
      .setInputCols(Array("x1", "x2", "x3", "x4", "x5"))
      .setOutputCol("features")

    val assembledDF = assembler.transform(df)

    // Create a LinearRegression model with specific parameters to ensure reproducibility
    val lr = new LinearRegression()
      .setMaxIter(1) // Minimal iterations
      .setRegParam(0.0) // No regularization
      .setElasticNetParam(0.0) // Pure L2 regularization (but 0 weight)
      .setStandardization(false) // Don't standardize features
      .setFitIntercept(true) // Fit intercept
      .setTol(1e-30) // Very small tolerance to force exact fit
      .setFeaturesCol("features")
      .setLabelCol("label")

    // Train the model - with our data and parameters, this should give us exact coefficients
    val model = lr.fit(assembledDF)

    // Print the model parameters
    println(s"Coefficients: ${model.coefficients}")
    println(s"Intercept: ${model.intercept}")

    // Make predictions on test data
    val testData = Seq(
      (1.0, 2.0, 3.0, 4.0, 5.0),
      (2.0, 3.0, 4.0, 5.0, 6.0)
    )

    val testDF = spark.createDataFrame(testData).toDF("x1", "x2", "x3", "x4", "x5")
    val testAssembledDF = assembler.transform(testDF)

    // Make predictions (which should be exactly 50.0 and 55.0)
    val predictions = model.transform(testAssembledDF).select("features", "prediction")

    predictions
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("SparkMLExample")
      .master("local[*]")
      .getOrCreate()

    val predictions = trainLinearRegressionModel(spark)
    predictions.show()

    spark.stop()
  }
}
