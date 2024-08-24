package myplugin
import mill._
/**
 * Example Mill plugin trait that adds a `line-count.txt`
 * to the resources of your `JavaModule`
 */
trait LineCountJavaModule extends mill.javalib.JavaModule{
  /** Total number of lines in module's source files */
  def lineCount = T{
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = T{
    os.write(T.dest / "line-count.txt", "" + lineCount())
    super.resources() ++ Seq(PathRef(T.dest))
  }
}