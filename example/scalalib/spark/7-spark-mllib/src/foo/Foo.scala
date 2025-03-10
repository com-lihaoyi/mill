package foo

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions.col
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.feature._
import org.apache.spark.ml.classification.DecisionTreeClassifier
import org.apache.spark.ml.evaluation.{
  BinaryClassificationEvaluator,
  MulticlassClassificationEvaluator
}

object Foo {
  val ModelPath = "./out/dt_model"
  val DataPath = "/data.csv"

  def main(args: Array[String]): Unit = {
    println("VALIDATION: Starting Spark MLlib Pipeline")

    val spark = SparkSession.builder()
      .appName("Spark MLlib")
      .master("local[*]")
      .getOrCreate()

    try {
      runPipeline(spark)
    } finally {
      spark.stop()
      println("VALIDATION: Spark session closed")
    }
  }

  def runPipeline(spark: SparkSession): Unit = {
    println("VALIDATION: Spark session initialized successfully")

    val data = loadData(spark)
    val model = trainOrLoadModel(data)
    val Array(_, testData) = data.randomSplit(Array(0.7, 0.3))
    val predictions = model.transform(testData)

    evaluateModel(predictions)
    validateModelPersistency(model)
  }

  def loadData(spark: SparkSession): DataFrame = {
    val dataPath = getClass.getResource(DataPath).getPath
    println(s"VALIDATION: Loading data from path: $dataPath")

    spark.read
      .option("header", "true")
      .csv(dataPath)
      .select(
        col("color"),
        col("price").cast("double"),
        col("label").cast("double")
      )
  }

  def trainOrLoadModel(data: DataFrame): PipelineModel = {
    try {
      PipelineModel.load(ModelPath)
    } catch {
      case _: Exception =>
        println("VALIDATION: Training new model")
        val model = createPipeline().fit(data)
        model.write.overwrite().save(ModelPath)
        model
    }
  }

  def createPipeline(): Pipeline = {
    val stages = Array(
      new StringIndexer().setInputCol("color").setOutputCol("colorIndex"),
      new OneHotEncoder().setInputCols(Array("colorIndex")).setOutputCols(Array("colorVec")),
      new VectorAssembler().setInputCols(Array("colorVec", "price")).setOutputCol("features"),
      new DecisionTreeClassifier().setLabelCol("label").setFeaturesCol("features").setMaxDepth(5)
    )
    new Pipeline().setStages(stages)
  }

  def evaluateModel(predictions: DataFrame): Unit = {
    val accuracy = new MulticlassClassificationEvaluator()
      .setMetricName("accuracy")
      .evaluate(predictions)

    val auc = new BinaryClassificationEvaluator()
      .setMetricName("areaUnderROC")
      .evaluate(predictions)

    println(s"VALIDATION: Model accuracy = $accuracy")
    println(s"VALIDATION: Model AUC = $auc")
  }

  def validateModelPersistency(model: PipelineModel): Unit = {
    model.write.overwrite().save(ModelPath)
    val reloadedModel = PipelineModel.load(ModelPath)
    println(s"VALIDATION: Model persistence validated at $ModelPath")
  }
}
