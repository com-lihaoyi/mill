package mill.main.graphviz

import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interception.logging.JavetStandardConsoleInterceptor
import com.caoccao.javet.interop.{V8Host, V8Runtime}
import guru.nidi.graphviz.attribute.Rank.RankDir
import guru.nidi.graphviz.attribute.{Rank, Shape, Style}
import guru.nidi.graphviz.engine.{AbstractJavascriptEngine, AbstractJsGraphvizEngine, ResultHandler}
import mill.api.PathRef
import mill.define.NamedTask
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedGraph}
import org.slf4j.LoggerFactory
import org.slf4j.Logger

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

    Graphviz.useEngine(new AbstractJsGraphvizEngine(true, () => new V8JavascriptEngine()) {})
    val gv = Graphviz.fromGraph(g).totalMemory(128 * 1024 * 1024)
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

class V8JavascriptEngine() extends AbstractJavascriptEngine {
  val LOG: Logger = LoggerFactory.getLogger(classOf[V8JavascriptEngine])
  val v8Runtime: V8Runtime = V8Host.getV8Instance().createV8Runtime()
  LOG.info("Starting V8 runtime...")
  LOG.info("Started V8 runtime. Initializing javascript...")
  val resultHandler = new ResultHandler
  val javetStandardConsoleInterceptor = new JavetStandardConsoleInterceptor(v8Runtime)
  javetStandardConsoleInterceptor.register(v8Runtime.getGlobalObject)

  class ResultHandlerInterceptor(resultHandler: ResultHandler) {
    @V8Function
    def result(s: String): Unit = resultHandler.setResult(s)

    @V8Function
    def error(s: String): Unit = resultHandler.setError(s)

    @V8Function
    def log(s: String): Unit = resultHandler.log(s)
  }
  val v8ValueObject = v8Runtime.createV8ValueObject
  v8Runtime.getGlobalObject.set("resultHandlerInterceptor", v8ValueObject)
  v8ValueObject.bind(new ResultHandlerInterceptor(resultHandler))

  v8Runtime.getExecutor(
    "var result = resultHandlerInterceptor.result; " +
      "var error = resultHandlerInterceptor.error; " +
      "var log = resultHandlerInterceptor.log; "
  ).execute()

  LOG.info("Initialized javascript.")

  override protected def execute(js: String): String = {
    v8Runtime.getExecutor(js).execute()
    resultHandler.waitFor
  }

  override def close(): Unit = v8Runtime.close()
}
