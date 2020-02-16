package mill.scalajslib

import java.io.{FileReader, StringWriter}
import javax.script.{ScriptContext, ScriptEngineManager}

object ScalaJsUtils {
  /* TODO Using Nashorn means that we do not support ECMAScript 2015, which
   * forces ScalaJSWorkerImpl to always use ES 5.1. We should a different
   * engine, perhaps Scala.js' own JSEnv, to perform these tests.
   */
  def runJS(path: os.Path): String = {
    val engineManager = new ScriptEngineManager(null)
    val engine = engineManager.getEngineByName("nashorn")
    val console = new Console
    val bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE)
    bindings.put("console", console)
    engine.eval(new FileReader(path.toIO))
    console.out.toString
  }
}

class Console {
  val out = new StringWriter()
  def log(s: String): Unit = out.append(s)
}
