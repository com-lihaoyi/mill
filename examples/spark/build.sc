import mill._
import mill.scalalib._
import mill.spark._

object WordCount extends SparkModule {
  def scalaVersion = "3.3.1"
  def mainClass = "example.WordCount"
}

object CsvEtl extends SparkModule {
  def scalaVersion = "3.3.1"
  def mainClass = "example.CsvETL"
}

object JoinsWindows extends SparkModule {
  def scalaVersion = "3.3.1"
  def mainClass = "example.JoinsWindows"
}

object UdfVsBuiltins extends SparkModule {
  def scalaVersion = "3.3.1"
  def mainClass = "example.UdfVsBuiltins"
}

object StreamingFile extends SparkModule {
  def scalaVersion = "3.3.1"
  def mainClass = "example.StreamingFile"
}
