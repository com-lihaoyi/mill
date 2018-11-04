package mill.scalajslib

import java.io.{FileReader, StringWriter}
import javax.script.{ScriptContext, ScriptEngineManager}

object ScalaJsUtils {
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
