package mill.graphviz

import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interception.logging.JavetStandardConsoleInterceptor
import com.caoccao.javet.interop.{V8Host, V8Runtime}
import guru.nidi.graphviz.engine.{AbstractJavascriptEngine, AbstractJsGraphvizEngine, ResultHandler}
import org.slf4j.LoggerFactory
import org.slf4j.Logger

import java.util.concurrent.Executors
import guru.nidi.graphviz.engine.{Format, Graphviz}
import scala.concurrent.{Await, ExecutionContext, Future, duration}

object GraphvizTools {

  def main(args: Array[String]): Unit = {
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors())

    val threadLocalJsEngines =
      new java.util.concurrent.ConcurrentHashMap[Thread, V8JavascriptEngine]()
    Graphviz.useEngine(
      new AbstractJsGraphvizEngine(
        true,
        () => {
          threadLocalJsEngines.putIfAbsent(Thread.currentThread(), V8JavascriptEngine())
          threadLocalJsEngines.get(Thread.currentThread())
        }
      ) {}
    )
    try {
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
      val futures =
        for (arg <- args.toSeq) yield Future {
          val Array(src, dest0, commaSepExtensions) = arg.split(";")
          val extensions = commaSepExtensions.split(',')
          val dest = os.Path(dest0)

          val gv = Graphviz.fromFile(new java.io.File(src)).totalMemory(128 * 1024 * 1024)

          val outputs = extensions
            .map(ext => Format.values().find(_.fileExtension == ext).head -> s"out.$ext")

          for ((fmt, name) <- outputs) gv.render(fmt).toFile((dest / name).toIO)
        }

      Await.result(Future.sequence(futures), duration.Duration.Inf)
    } finally executor.shutdown()
  }
}

class V8JavascriptEngine() extends AbstractJavascriptEngine {
  val LOG: Logger = LoggerFactory.getLogger(classOf[V8JavascriptEngine])
  val v8Runtime: V8Runtime = V8Host.getV8Instance().createV8Runtime()
  LOG.info("Starting V8 runtime...")
  LOG.info("Started V8 runtime. Initializing javascript...")
  val resultHandler = new ResultHandler
  val javetStandardConsoleInterceptor = JavetStandardConsoleInterceptor(v8Runtime)
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
  v8ValueObject.bind(ResultHandlerInterceptor(resultHandler))

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
