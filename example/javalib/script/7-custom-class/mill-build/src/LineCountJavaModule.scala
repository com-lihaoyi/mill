package millbuild
import mill.*, javalib.*, script.*

class LineCountJavaModule(scriptConfig: mill.api.ScriptModule.Config)
    extends mill.script.JavaModule(scriptConfig) {

  /** Total number of lines in module source files */
  def lineCount = Task {
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = Task {
    os.write(Task.dest / "line-count.txt", "" + lineCount())
    super.resources() ++ Seq(PathRef(Task.dest))
  }
}
