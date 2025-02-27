package foo

import utest._
import org.apache.spark._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

object FooTests extends TestSuite {
  val spark: SparkSession = SparkSession.builder()
    .appName("FooTests")
    .master("local[*]")
    .getOrCreate()
  val sc: SparkContext = spark.sparkContext
  sc.setLogLevel("ERROR")

  val tests = Tests {
    test("loadVertices should load vertices correctly") {
      val path = getClass.getResource("/data/vertices.txt").getPath
      val vertices = Foo.loadVertices(sc, path)
      val collected = vertices.collect()
      assert(collected.length == 6) // 6 vertices in the test file
    }

    test("loadEdges should load edges correctly") {
      val path = getClass.getResource("/data/edges.txt").getPath
      val edges = Foo.loadEdges(sc, path)
      val collected = edges.collect()
      assert(collected.length == 8) // 8 edges in the test file
    }

    test("analyzeGraph should compute triangles, ranks, and components correctly") {
      val verticesPath = getClass.getResource("/data/vertices.txt").getPath
      val edgesPath = getClass.getResource("/data/edges.txt").getPath
      val vertices = Foo.loadVertices(sc, verticesPath)
      val edges = Foo.loadEdges(sc, edgesPath)

      val (triangles, ranks, components) = Foo.analyzeGraph(spark, vertices, edges)

      // Test triangle counts
      val triangleCounts = triangles.collect()
      assert(triangleCounts.length == 6) // 6 vertices
      assert(triangleCounts.contains((1L, 1))) // Home has 1 triangle
      assert(triangleCounts.contains((2L, 1))) // About has 1 triangle
      assert(triangleCounts.contains((3L, 1))) // Products has 1 triangle
      assert(triangleCounts.contains((4L, 0))) // Contact has 0 triangle
      assert(triangleCounts.contains((5L, 0))) // FAQ has 0 triangle
      assert(triangleCounts.contains((6L, 0))) // Blog has 0 triangle

      // Test PageRank
      val pageRanks = ranks.collect()
      assert(pageRanks.length == 6) // 6 vertices
      assert(pageRanks.exists { case (_, rank) => rank > 0.0 }) // All ranks should be > 0

      // Test connected components
      val connectedComponents = components.collect()
      assert(connectedComponents.length == 6) // 6 vertices
      assert(connectedComponents.forall { case (_, component) =>
        component == 1L
      }) // All in one component
    }
  }

  override def utestAfterAll(): Unit = {
    spark.stop()
  }
}
