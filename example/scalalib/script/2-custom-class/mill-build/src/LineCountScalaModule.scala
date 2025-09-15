package millbuild
import mill.*, javalib.*, scalalib.*

class LineCountScalaModule(val millScriptFile: os.Path, override val moduleDeps: Seq[JavaModule])
    extends mill.script.Scala.Base {

  /** Total number of lines in module source files */
  def lineCount = Task {
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = Task {
    os.write(Task.dest / "line-count.txt", "" + lineCount())
    super.resources() ++ Seq(PathRef(Task.dest))
  }

  lazy val millDiscover = mill.api.Discover[this.type]
}
