package foo

import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions._
import com.typesafe.scalalogging.LazyLogging

case class LogRecord(timestamp: String, level: String, endpoint: String, message: String)

object Foo extends LazyLogging {

  def main(args: Array[String]): Unit = {
    implicit val spark: SparkSession = createSparkSession()

    val resourcePath = getResourcePath(args)
    val logDS = readLogData(resourcePath)
    val cleanedDF = cleanData(logDS)

    val errorsPerHour = computeErrorsPerHour(cleanedDF)
    val commonErrors = computeCommonErrors(cleanedDF)
    val errorProneEndpoints = computeErrorProneEndpoints(cleanedDF)

    displayResults(errorsPerHour, commonErrors, errorProneEndpoints)

    spark.stop()
  }

  def createSparkSession(): SparkSession = {
    SparkSession.builder()
      .appName("Semi-Realistic")
      .master("local[*]")
      .config("spark.ui.port", "4052")
      .getOrCreate()
  }

  def getResourcePath(args: Array[String]): String = {
    args.headOption
      .orElse(Option(this.getClass.getResource("/logging.csv")).map(_.getPath))
      .getOrElse(throw new RuntimeException(
        "logging.csv not provided as argument and not found in resources"
      ))
  }

  def readLogData(path: String)(implicit spark: SparkSession): Dataset[LogRecord] = {
    import spark.implicits._
    spark.read
      .option("header", "true")
      .csv(path)
      .as[LogRecord]
  }

  def cleanData(logDS: Dataset[LogRecord])(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    logDS
      .withColumn("hour", hour(to_timestamp($"timestamp", "yyyy-MM-dd HH:mm:ss")))
      .cache()
  }

  def computeErrorsPerHour(cleanedDF: DataFrame)(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    cleanedDF
      .filter($"level" === "ERROR")
      .groupBy("hour")
      .count()
      .orderBy("hour")
  }

  def computeCommonErrors(cleanedDF: DataFrame)(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    cleanedDF
      .filter($"level" === "ERROR")
      .groupBy("message")
      .count()
      .orderBy(desc("count"))
  }

  def computeErrorProneEndpoints(cleanedDF: DataFrame)(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    cleanedDF
      .filter($"level" === "ERROR")
      .groupBy("endpoint")
      .count()
      .orderBy(desc("count"))
  }

  def displayResults(
      errorsPerHour: DataFrame,
      commonErrors: DataFrame,
      errorProneEndpoints: DataFrame
  ): Unit = {
    println("\nErrors per hour:")
    errorsPerHour.show()

    println("\nMost common error messages:")
    commonErrors.show(10, truncate = false)

    println("\nMost error-prone endpoints:")
    errorProneEndpoints.show(10)
  }
}
