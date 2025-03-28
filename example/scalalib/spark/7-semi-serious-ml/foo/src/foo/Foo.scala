package foo

import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.classification.{LogisticRegression, LogisticRegressionModel}
import org.apache.spark.ml.feature.{HashingTF, StringIndexer, Tokenizer}
import org.apache.spark.sql.{DataFrame, SparkSession}

object Foo {

  def buildTextClassifier(spark: SparkSession): DataFrame = {
    // Sample data for text classification
    val data = Seq(
      ("Machine learning is transforming industries", "tech_ai"),
      ("The quarterback threw a touchdown pass", "sports"),
      ("Neural networks can recognize patterns", "tech_ai"),
      ("The stock market rallied after the announcement", "business"),
      ("Deep learning algorithms require significant data", "tech_ai"),
      ("The team won the championship game", "sports"),
      ("Investors are cautious about the upcoming IPO", "business"),
      ("GPT models have revolutionized natural language processing", "tech_ai"),
      ("The basketball player scored 30 points", "sports"),
      ("The company announced quarterly earnings", "business")
    )

    // Create a DataFrame with text and label columns
    val trainingDF = spark.createDataFrame(data).toDF("text", "label")

    // Convert string labels to indices
    val labelIndexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("indexedLabel")
      .fit(trainingDF)

    // Configure ML pipeline
    val tokenizer = new Tokenizer()
      .setInputCol("text")
      .setOutputCol("words")

    val hashingTF = new HashingTF()
      .setInputCol("words")
      .setOutputCol("features")
      .setNumFeatures(1000)

    val lr = new LogisticRegression()
      .setMaxIter(10)
      .setRegParam(0.01)
      .setLabelCol("indexedLabel")
      .setFeaturesCol("features")

    // Create and train pipeline
    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, tokenizer, hashingTF, lr))

    val model = pipeline.fit(trainingDF)

    // Create test data
    val testData = Seq(
      ("Machine learning models are becoming more accurate", "tech_ai"),
      ("The quarterback made a game-winning throw", "sports"),
      ("Neural networks can learn complex relationships", "tech_ai"),
      ("The stock market closed higher yesterday", "business"),
      ("Deep learning is a subset of machine learning", "tech_ai")
    )

    val testDF = spark.createDataFrame(testData).toDF("text", "label")

    // Make predictions
    val predictions = model.transform(testDF)
      .select("text", "label", "probability", "prediction")

    // Convert prediction indices back to original labels
    val labelToIndex = labelIndexer.labelsArray(0).zipWithIndex.toMap
    val indexToLabel = labelToIndex.map(_.swap)

    // Print prediction mappings for educational purposes
    println("Label Mappings:")
    labelToIndex.foreach { case (label, index) =>
      println(s"$label -> $index")
    }

    // Display some explanatory information
    println("\nFeature Importance:")
    val lrModel = model.stages.last.asInstanceOf[LogisticRegressionModel]
    println(s"Model coefficients: ${lrModel.coefficientMatrix}")

    predictions
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("SimpleTextClassification")
      .master("local[*]")
      .getOrCreate()

    val predictions = buildTextClassifier(spark)

    // Show results
    predictions.show()

    spark.stop()
  }
}
