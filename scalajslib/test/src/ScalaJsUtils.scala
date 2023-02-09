package mill.scalajslib

object ScalaJsUtils {
  def runJS(path: os.Path): String = {
    os.proc("node", path).call().out.text()
  }
}
