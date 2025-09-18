package millbuild
import mill.*, javalib.*, kotlinlib.*

class LineCountKotlinModule(scriptConf: mill.javalib.SimpleModule.Config)
    extends mill.kotlinlib.KotlinModule.Simple(scriptConf) {

  /** Total number of lines in module source files */
  def lineCount = Task {
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = Task {
    os.write(Task.dest / "line-count.txt", "" + lineCount())
    super.resources() ++ Seq(PathRef(Task.dest))
  }

  override lazy val millDiscover = mill.api.Discover[this.type]
}
