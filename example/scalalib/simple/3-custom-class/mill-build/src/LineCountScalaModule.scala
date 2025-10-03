package millbuild
import mill.*, javalib.*, scalalib.*

class LineCountScalaModule(scriptConf: mill.simple.SimpleModule.Config)
    extends mill.simple.ScalaModule(scriptConf) {

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
