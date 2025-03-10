package foo

import utest._
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.classification.DecisionTreeClassifier
import org.apache.spark.sql.functions.col
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator

object FooTests extends TestSuite {
  val spark = SparkSession.builder()
    .master("local[*]")
    .appName("Spark MLlib Tests")
    .getOrCreate()

  val tests = Tests {
    test("dataLoading") {
      val data = Foo.loadData(spark)
      assert(data.count() > 0)
      assert(data.columns.sorted.toSeq == Seq("color", "label", "price").sorted)
    }

    test("pipelineConstruction") {
      val pipeline = Foo.createPipeline()
      assert(pipeline.getStages.length == 4)
      assert(pipeline.getStages.last.isInstanceOf[DecisionTreeClassifier])
    }

    test("modelTraining") {
      val data = Foo.loadData(spark)
      val model = Foo.trainOrLoadModel(data)
      assert(model.stages.length == 4)
      assert(model.transform(data.limit(10)).columns.contains("prediction"))
    }

    test("modelPersistence") {
      val model = PipelineModel.load(Foo.ModelPath)
      val testData = spark.createDataFrame(Seq(
        ("red", 25.0, 0.0),
        ("green", 35.0, 1.0)
      )).toDF("color", "price", "label")

      val predictions = model.transform(testData)
      assert(predictions.select("prediction").count() == 2)
    }

    test("evaluationMetrics") {
      val data = Foo.loadData(spark)
      val model = PipelineModel.load(Foo.ModelPath)
      val predictions = model.transform(data.limit(10))

      val accuracy = evaluateModel(predictions)
      assert(accuracy >= 0.0 && accuracy <= 1.0)
    }
  }

  override def utestAfterAll(): Unit = {
    spark.stop()
  }

  // Fixed helper method
  private def evaluateModel(predictions: DataFrame): Double = {
    new MulticlassClassificationEvaluator()
      .setMetricName("accuracy")
      .evaluate(predictions)
  }
}
