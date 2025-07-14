package myplugin
import mill.*

/**
 * Example Mill plugin trait that adds a `line-count.txt`
 * to the resources of your `JavaModule`
 */
trait LineCountJavaModule extends mill.javalib.JavaModule {

  /** Name of the file containing the line count that we create in the resource path */
  def lineCountResourceFileName: T[String]

  /** Total number of lines in module source files */
  def lineCount = Task {
    allSourceFiles().map(f => os.read.lines(f.path).size).sum
  }

  /** Generate resources using lineCount of sources */
  override def resources = Task {
    os.write(Task.dest / lineCountResourceFileName(), "" + lineCount())
    super.resources() ++ Seq(PathRef(Task.dest))
  }
}
