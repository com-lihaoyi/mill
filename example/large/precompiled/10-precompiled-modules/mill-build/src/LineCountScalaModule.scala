package millbuild
import mill.*, scalalib.*

class LineCountScalaModule(scriptConfig: mill.api.ScriptModule.Config)
    extends mill.script.ScalaModule(scriptConfig) {

  // Use standard directory-based source handling instead of single-file script handling
  private val moduleRoot: os.Path = scriptConfig.scriptFile / os.up
  override def sources: T[Seq[PathRef]] = Task.Sources(moduleRoot / "src")
  override def allSources: T[Seq[PathRef]] = Task { sources() }
  override def allSourceFiles: T[Seq[PathRef]] = Task {
    val exts = sourceFileExtensions
    for {
      root <- allSources()
      if os.exists(root.path)
      path <- if (os.isDir(root.path)) os.walk(root.path) else Seq(root.path)
      if os.isFile(path) && exts.exists(ex => path.last.endsWith(s".$ex"))
    } yield PathRef(path)
  }

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
