package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("linearRegressionModel should predict values correctly") {
      val spark = SparkSession.builder()
        .appName("SparkMLTest")
        .master("local[*]")
        .getOrCreate()

      try {
        val predictions = Foo.trainLinearRegressionModel(spark)

        // Get the prediction results as a collection of rows
        val predictionRows = predictions.collect()

        // Verify we have the expected number of predictions
        assert(predictionRows.length == 2)

        // Extract the prediction values
        val predictedValues = predictionRows.map(_.getDouble(1))

        // The exact values from actual execution
        assert(predictedValues(0) == 50.00000000000003)
        assert(predictedValues(1) == 55.000000000000014)
      } finally {
        // Always stop the SparkSession when done
        spark.stop()
      }
    }
  }
}
