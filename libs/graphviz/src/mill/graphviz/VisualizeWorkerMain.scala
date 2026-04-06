package mill.graphviz

import guru.nidi.graphviz.attribute.Rank.RankDir
import guru.nidi.graphviz.attribute.{Rank, Shape, Style}
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedGraph}

object VisualizeWorkerMain {
  def main(args: Array[String]): Unit = {
    args.toSeq match {
      case Seq(payload0, dest0) =>
        render(payload = os.Path(payload0), dest = os.Path(dest0))
      case _ =>
        sys.error("Expected arguments: <payload-json-path> <dest-dir-path>")
    }
  }

  private def render(payload: os.Path, dest: os.Path): Unit = {
    val parsed = ujson.read(os.read(payload))
    val selected = parsed("tasks").arr.map(_.str).toSet
    val edges = parsed("edges").arr.map { entry =>
      val src = entry("src").str
      val dests = entry("dests").arr.map(_.str).toArray.distinct
      (src, dests)
    }.toArray

    val indexToTask = edges.flatMap { case (k, vs) => Iterator(k) ++ vs }.distinct
    val taskToIndex = indexToTask.zipWithIndex.toMap

    val jgraph = new SimpleDirectedGraph[Int, DefaultEdge](classOf[DefaultEdge])
    for (i <- indexToTask.indices) jgraph.addVertex(i)
    for ((src, dests) <- edges; target <- dests) {
      jgraph.addEdge(taskToIndex(src), taskToIndex(target))
    }
    org.jgrapht.alg.TransitiveReduction.INSTANCE.reduce(jgraph)

    import guru.nidi.graphviz.model.Factory.*
    val nodes = indexToTask.map(t =>
      node(t)
        .`with`(if (selected.contains(t)) Style.SOLID else Style.DASHED)
        .`with`(Shape.BOX)
    )

    var g = graph("example1").directed
    for (i <- indexToTask.indices) {
      for {
        target <- edges(i)._2
        j = taskToIndex(target)
        if jgraph.containsEdge(i, j)
      } {
        g = g.`with`(nodes(j).link(nodes(i)))
      }
    }
    g = g.graphAttr().`with`(Rank.dir(RankDir.LEFT_TO_RIGHT))

    val graphFile = os.temp(prefix = "mill-visualize-", suffix = ".dot")
    try {
      os.write.over(graphFile, g.toString)
      GraphvizTools.main(Array(s"$graphFile;$dest;txt,dot,json,png,svg"))
    } finally {
      os.remove(graphFile, checkExists = false)
    }
  }
}
