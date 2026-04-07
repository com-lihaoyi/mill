package millbuild
import mill.*, javalib.*

class LineCountJavaModule(val scriptConfig: mill.api.ScriptModule.Config)
    extends mill.javalib.JavaModule with mill.api.ScriptModule {

  override lazy val millDiscover = mill.api.Discover[this.type]

  /** Total number of lines in module source files */
  def lineCount: T[Int] = Task {
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources: T[Seq[PathRef]] = Task {
    os.write(Task.dest / "line-count.txt", "" + lineCount())
    super.resources() ++ Seq(PathRef(Task.dest))
  }
}
