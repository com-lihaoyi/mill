package myplugin
import mill._
/**
 * Example Mill plugin trait that adds a `line-count.txt`
 * to the resources of your `JavaModule`
 */
trait LineCountJavaModule extends mill.javalib.JavaModule{
  /** Name of the file containing the line count that we create in the resource path */
  def lineCountResourceFileName: T[String]

  /** Total number of lines in module's source files */
  def lineCount = T{
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = T{
    os.write(T.dest / lineCountResourceFileName(), "" + lineCount())
    super.resources() ++ Seq(PathRef(T.dest))
  }
}