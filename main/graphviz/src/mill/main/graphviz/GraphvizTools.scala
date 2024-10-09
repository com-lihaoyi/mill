package mill.main.graphviz

import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interception.logging.JavetStandardConsoleInterceptor
import com.caoccao.javet.interop.{V8Host, V8Runtime}
import guru.nidi.graphviz.engine.{AbstractJavascriptEngine, AbstractJsGraphvizEngine, ResultHandler}
import org.slf4j.LoggerFactory
import org.slf4j.Logger

object GraphvizTools {

  def main(args: Array[String]): Unit = {
    val Array(src, dest0) = args

    val dest = os.Path(dest0)
    import guru.nidi.graphviz.engine.{Format, Graphviz}

    Graphviz.useEngine(new AbstractJsGraphvizEngine(true, () => new V8JavascriptEngine()) {})
    val gv = Graphviz.fromFile(new java.io.File(src)).totalMemory(128 * 1024 * 1024)
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
