package foo

import org.apache.spark.{SparkConf, SparkContext}

object Foo {

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setAppName("Spark RDD Example")
      .setMaster("local[*]")
    val sc = new SparkContext(conf)

    try {
      // 1. Basic RDD Creation & Transformations
      val numbers = sc.parallelize(1 to 20)
      val squares = numbers.map(x => x * x)
      val evenSquares = squares.filter(_ % 2 == 0)

      // 2. Key-Value Pair Operations
      val pairs = numbers.map(x => (x % 3, x))
      val reduced = pairs.reduceByKey(_ + _)
      val grouped = pairs.groupByKey()

      // 3. Advanced Transformations
      val consistentSample = numbers.sample(withReplacement = false, fraction = 0.25, seed = 42L)
      val distinct = sc.parallelize(Seq(1, 1, 2, 3, 4, 4)).distinct()
      val union = numbers.union(sc.parallelize(15 to 30))

      // 4. Actions & Results
      println("Basic Transformations:")
      println(s"Squares: ${squares.collect().mkString(", ")}")
      println(s"Even Squares: ${evenSquares.collect().mkString(", ")}")

      println("\nKey-Value Operations:")
      println(s"ReduceByKey: ${reduced.collect().mkString(", ")}")
      println(s"GroupByKey: ${grouped.mapValues(_.toList).collect().mkString(", ")}")

      println("\nAdvanced Operations:")
      println(s"Consistent Sample: ${consistentSample.collect().mkString(", ")}")
      println(s"Distinct: ${distinct.collect().mkString(", ")}")
      println(s"Union: ${union.collect().mkString(", ")}")

      println("\nFinal Counts:")
      println(s"Total numbers: ${numbers.count()}")
      println(s"Total squares: ${squares.count()}")
      println(s"Total pairs: ${pairs.count()}")

    } finally {
      sc.stop()
    }
  }
}
