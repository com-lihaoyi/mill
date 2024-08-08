//// SNIPPET:BUILD

import mill._, javalib._

object foo extends RootModule with JavaModule {
  /** Total number of lines in module's source files */
  def lineCount = Task {
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = Task {
    os.write(Task.dest / "line-count.txt", "" + lineCount())
    Seq(PathRef(Task.dest))
  }
}
