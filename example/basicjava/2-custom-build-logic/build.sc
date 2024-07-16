//// SNIPPET:BUILD

import mill._, javalib._

object foo extends RootModule with JavaModule {
  /** Total number of lines in module's source files */
  def lineCount = T{
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = T{
    os.write(T.dest / "line-count.txt", "" + lineCount())
    Seq(PathRef(T.dest))
  }
}
