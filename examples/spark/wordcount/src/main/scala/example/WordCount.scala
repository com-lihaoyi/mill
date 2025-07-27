package example

import org.apache.spark.sql.SparkSession

object WordCount {
  def main(args: Array[String]): Unit = {
    // parse simple --input and --output arguments
    val inputPath  = args.sliding(2).find(_(0) == "--input").map(_(1)).getOrElse("text.txt")
    val outputPath = args.sliding(2).find(_(0) == "--output").map(_(1)).getOrElse("out")

    val spark = SparkSession.builder().appName("WordCount").getOrCreate()
    import spark.implicits._
    val lines = spark.read.textFile(inputPath)
    val counts = lines.flatMap(_.split("\\s+")).groupByKey(identity).count().toDF("word","count").orderBy("word")
    counts.write.mode("overwrite").csv(outputPath)
    counts.show(false)
    spark.stop()
  }
}
