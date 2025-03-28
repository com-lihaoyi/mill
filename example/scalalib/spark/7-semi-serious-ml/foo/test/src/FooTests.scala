package foo

import org.apache.spark.sql.SparkSession
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("textClassifier should classify text samples correctly") {
      val spark = SparkSession.builder()
        .appName("TextClassificationTest")
        .master("local[*]")
        .getOrCreate()

      val predictions = Foo.buildTextClassifier(spark)

      val results = predictions.collect()

      // Check that we have the expected number of predictions
      assert(results.length == 5)

      // Check that our model correctly classifies some samples
      // Get predictions for tech_ai examples
      val techAiPredictions = results
        .filter(_.getString(1) == "tech_ai")
        .map(_.getDouble(3))

      // Check that most tech_ai examples are classified as the same category
      // We check if at least 2 out of 3 are in the same category
      val common = techAiPredictions
        .groupBy(identity)
        .maxBy(_._2.length)
        ._1

      val correctlyClassified = techAiPredictions.count(_ == common)
      assert(correctlyClassified >= 2)

      // Stop the SparkSession
      spark.stop()
    }
  }
}
