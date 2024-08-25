package mill.main.graphviz

import guru.nidi.graphviz.attribute.Rank.RankDir
import guru.nidi.graphviz.attribute.{Rank, Shape, Style}
import mill.api.PathRef
import mill.define.NamedTask
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedGraph}

object GraphvizTools {

  def apply(targets: Seq[NamedTask[Any]], rs: Seq[NamedTask[Any]], dest: os.Path): Seq[PathRef] = {
    val (sortedGroups, transitive) = mill.eval.Plan.plan(rs)

    val goalSet = rs.toSet
    import guru.nidi.graphviz.engine.{Format, Graphviz}
    import guru.nidi.graphviz.model.Factory._

    val edgesIterator =
      for ((k, vs) <- sortedGroups.items())
        yield (
          k,
          for {
            v <- vs.items
            dest <- v.inputs.collect { case v: NamedTask[Any] => v }
            if goalSet.contains(dest)
          } yield dest
        )

    val edges = edgesIterator.map { case (k, v) => (k, v.toArray.distinct) }.toArray

    val indexToTask = edges.flatMap { case (k, vs) => Iterator(k.task) ++ vs }.distinct
    val taskToIndex = indexToTask.zipWithIndex.toMap

    val jgraph = new SimpleDirectedGraph[Int, DefaultEdge](classOf[DefaultEdge])

    for (i <- indexToTask.indices) jgraph.addVertex(i)
    for ((src, dests) <- edges; dest <- dests) {
      jgraph.addEdge(taskToIndex(src.task), taskToIndex(dest))
    }

    org.jgrapht.alg.TransitiveReduction.INSTANCE.reduce(jgraph)
    val nodes = indexToTask.map(t =>
      node(sortedGroups.lookupValue(t).render)
        .`with` {
          if (targets.contains(t)) Style.SOLID
          else Style.DASHED
        }
        .`with`(Shape.BOX)
    )

    var g = graph("example1").directed
    for (i <- indexToTask.indices) {
      for {
        e <- edges(i)._2
        j = taskToIndex(e)
        if jgraph.containsEdge(i, j)
      } {
        g = g.`with`(nodes(j).link(nodes(i)))
      }
    }

    g = g.graphAttr().`with`(Rank.dir(RankDir.LEFT_TO_RIGHT))

    val gv = Graphviz.fromGraph(g).totalMemory(100 * 1000 * 1000)
    val outputs = Seq(
      Format.PLAIN -> "out.txt",
      Format.XDOT -> "out.dot",
      Format.JSON -> "out.json",
      Format.PNG -> "out.png",
      Format.SVG -> "out.svg"
    )
    for ((fmt, name) <- outputs) {
      gv.render(fmt).toFile((dest / name).toIO)
    }
    outputs.map(x => mill.PathRef(dest / x._2))
  }
}
