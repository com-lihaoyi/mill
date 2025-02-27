package foo

import org.apache.spark._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

object Foo {
  type VertexRDD = RDD[(VertexId, String)]
  type EdgeRDD = RDD[Edge[Int]]

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Spark GraphX")
      .master("local[*]")
      .getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("ERROR")

    // Load data
    val vertices = loadVertices(sc, getClass.getResource("/data/vertices.txt").getPath)
    val edges = loadEdges(sc, getClass.getResource("/data/edges.txt").getPath)

    // Analyze graph
    val (triangles, ranks, components) = analyzeGraph(spark, vertices, edges)

    // Format and print results
    printResults(spark, vertices, triangles, ranks, components)

    spark.stop()
  }

  def loadVertices(sc: SparkContext, path: String): VertexRDD = {
    sc.textFile(path).map { line =>
      val fields = line.split(",")
      (fields(0).toLong, fields(1))
    }
  }

  def loadEdges(sc: SparkContext, path: String): EdgeRDD = {
    sc.textFile(path).map { line =>
      val fields = line.split(",")
      Edge(fields(0).toLong, fields(1).toLong, fields(2).toInt)
    }
  }

  def analyzeGraph(spark: SparkSession, vertices: VertexRDD, edges: EdgeRDD) = {
    import spark.implicits._

    val graph = Graph(vertices, edges)
      .partitionBy(PartitionStrategy.CanonicalRandomVertexCut)

    val triangles = graph.triangleCount().vertices
    val ranks = graph.pageRank(0.0001).vertices
    val components = graph.connectedComponents().vertices

    (triangles, ranks, components)
  }

  def printResults(
      spark: SparkSession,
      vertices: VertexRDD,
      triangles: RDD[(VertexId, Int)],
      ranks: RDD[(VertexId, Double)],
      components: RDD[(VertexId, VertexId)]
  ) = {
    import spark.implicits._

    // Triangle Counting
    val triangleDF = vertices.join(triangles)
      .map { case (id, (name, count)) => (name, count) }
      .toDF("Name", "TriangleCount")
    println("Triangle Counting Results:")
    triangleDF.show()

    // PageRank
    val rankDF = vertices.join(ranks)
      .map { case (id, (name, rank)) => (name, f"${rank}%.4f") }
      .toDF("Name", "PageRank")
    println("PageRank Results:")
    rankDF.show()

    // Connected Components
    val compDF = vertices.join(components)
      .map { case (id, (name, comp)) => (name, comp) }
      .toDF("Name", "Component")
    println("Connected Components Results:")
    compDF.show()
  }
}
