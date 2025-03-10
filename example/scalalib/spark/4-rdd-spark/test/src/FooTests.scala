package foo

import org.apache.spark.{SparkConf, SparkContext}
import utest._

object FooTests extends TestSuite {
  // Initialize SparkContext for tests
  val conf = new SparkConf()
    .setAppName("Spark RDD Tests")
    .setMaster("local[*]")
  val sc = new SparkContext(conf)

  // Tests will run in parallel, so we need to ensure proper cleanup
  override def utestAfterAll(): Unit = {
    sc.stop()
  }

  val tests = Tests {
    test("Basic Transformations") {
      val numbers = sc.parallelize(1 to 20)
      val squares = numbers.map(x => x * x)
      val evenSquares = squares.filter(_ % 2 == 0)

      // Test squares
      assert(squares.collect().toList == List(
        1, 4, 9, 16, 25, 36, 49, 64, 81, 100,
        121, 144, 169, 196, 225, 256, 289, 324, 361, 400
      ))

      // Test even squares
      assert(evenSquares.collect().toList == List(
        4, 16, 36, 64, 100, 144, 196, 256, 324, 400
      ))
    }

    test("Key-Value Operations") {
      val numbers = sc.parallelize(1 to 20)
      val pairs = numbers.map(x => (x % 3, x))
      val reduced = pairs.reduceByKey(_ + _)
      val grouped = pairs.groupByKey()

      // Test reduceByKey
      assert(reduced.collect().toMap == Map(
        0 -> 63, // 3+6+9+12+15+18
        1 -> 70, // 1+4+7+10+13+16+19
        2 -> 77 // 2+5+8+11+14+17+20
      ))

      // Test groupByKey
      val groupedResult = grouped.mapValues(_.toList).collect().toMap
      assert(groupedResult(0).sorted == List(3, 6, 9, 12, 15, 18))
      assert(groupedResult(1).sorted == List(1, 4, 7, 10, 13, 16, 19))
      assert(groupedResult(2).sorted == List(2, 5, 8, 11, 14, 17, 20))
    }

    test("Advanced Transformations") {
      val numbers = sc.parallelize(1 to 20)

      // Test consistent sampling
      val sample1 = numbers.sample(withReplacement = false, 0.25, 42L)
      val sample2 = numbers.sample(withReplacement = false, 0.25, 42L)
      assert(sample1.collect().toList == sample2.collect().toList)

      // Test distinct
      val duplicates = sc.parallelize(Seq(1, 1, 2, 3, 4, 4))
      assert(duplicates.distinct().collect().toSet == Set(1, 2, 3, 4))

      // Test union
      val rdd1 = sc.parallelize(1 to 5)
      val rdd2 = sc.parallelize(3 to 7)
      assert(rdd1.union(rdd2).collect().toSet == Set(1, 2, 3, 4, 5, 6, 7))
    }

    test("Count Operations") {
      val numbers = sc.parallelize(1 to 20)
      val pairs = numbers.map(x => (x % 3, x))

      assert(numbers.count() == 20)
      assert(pairs.count() == 20)

      val emptyRDD = sc.parallelize(Seq.empty[Int])
      assert(emptyRDD.count() == 0)
    }
  }
}
