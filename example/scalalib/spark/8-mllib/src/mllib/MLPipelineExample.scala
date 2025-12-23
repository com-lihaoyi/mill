package mllib

import org.apache.spark.sql.SparkSession
import org.apache.spark.ml.feature.{VectorAssembler, StringIndexer}
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator

object MLPipelineExample {

  case class Customer(
    customer: Int,
    usage_mins: Double,
    support_calls: Int,
    monthly_charges: Double,
    churn: String
  )

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("MLPipelineExample")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    println("=== Spark MLlib Pipeline Example ===")

    // Create sample customer churn data
    val customers = Seq(
      Customer(1, 350.0, 2, 65.0, "no"),
      Customer(2, 120.0, 5, 45.0, "yes"),
      Customer(3, 500.0, 1, 85.0, "no"),
      Customer(4, 80.0, 8, 35.0, "yes"),
      Customer(5, 400.0, 0, 75.0, "no"),
      Customer(6, 150.0, 6, 50.0, "yes"),
      Customer(7, 450.0, 1, 80.0, "no"),
      Customer(8, 100.0, 7, 40.0, "yes"),
      Customer(9, 380.0, 2, 70.0, "no"),
      Customer(10, 90.0, 9, 30.0, "yes")
    ).toDF()

    println("=== Training Data ===")
    customers.show()

    // Split data into training and test sets
    val Array(trainingData, testData) = customers.randomSplit(Array(0.7, 0.3), seed = 42)

    // Stage 1: Convert label column from string to numeric index
    val labelIndexer = new StringIndexer()
      .setInputCol("churn")
      .setOutputCol("label")

    // Stage 2: Assemble feature columns into a single vector column
    val assembler = new VectorAssembler()
      .setInputCols(Array("usage_mins", "support_calls", "monthly_charges"))
      .setOutputCol("features")

    // Stage 3: Create logistic regression model
    val lr = new LogisticRegression()
      .setMaxIter(10)
      .setRegParam(0.01)

    // Build the pipeline
    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, assembler, lr))

    // Train the model
    val model = pipeline.fit(trainingData)
    println("=== Model trained successfully ===")

    // Make predictions on test data
    val predictions = model.transform(testData)

    println("=== Predictions ===")
    predictions.select("customer", "churn", "probability", "prediction").show()

    // Evaluate the model
    val evaluator = new BinaryClassificationEvaluator()
      .setLabelCol("label")
      .setRawPredictionCol("rawPrediction")
      .setMetricName("areaUnderROC")

    val auc = evaluator.evaluate(predictions)
    println(s"=== Model Accuracy: AUC = ${"%.4f".format(auc)} ===")

    // Show model coefficients
    val lrModel = model.stages(2).asInstanceOf[org.apache.spark.ml.classification.LogisticRegressionModel]
    println(s"Coefficients: ${lrModel.coefficients}")
    println(s"Intercept: ${lrModel.intercept}")

    spark.stop()
  }
}
