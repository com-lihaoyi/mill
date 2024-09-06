package mill
package contrib.checkstyle

import mill.api.{Loose, PathRef}
import mill.scalalib.{DepSyntax, JavaModule, ScalaModule}
import mill.util.Jvm

trait CheckstyleModule extends JavaModule {

  def checkstyle: T[PathRef] = T {

    val cp = checkstyleClasspath().map(_.path)
    val opts = checkstyleOptions()
    val conf = checkstyleConfig()
    val fmt = checkstyleFormat()
    val out = T.dest / checkstyleOutput()
    val srcs = sources().map(_.path.toString())
    val thrw = checkstyleThrow()

    val args = Seq.newBuilder[String]
      .++=(opts)
      .+=("-c")
      .+=(conf.path.toString())
      .+=("-f")
      .+=(fmt.toString())
      .+=("-o")
      .+=(out.toString())
      .++=(srcs)
      .result()

    T.log.info(s"generating checkstyle $fmt report ...")
    args.foreach(s => T.log.debug(s"  $s"))

    val exit = Jvm.callSubprocess(
      mainClass = "com.puppycrawl.tools.checkstyle.Main",
      classPath = cp,
      mainArgs = args,
      workingDir = T.dest,
      check = false
    ).exitCode

    if (exit == 0) {
      T.log.info("checkstyle found no problems")
      T.log.info(s"  $out")
    } else if (exit > 0 && os.exists(out)) {
      val msg = s"checkstyle found $exit problem(s)"

      T.log.error(msg)
      T.log.error(s"  $out")
      if (thrw) {
        throw new RuntimeException(msg)
      }
    } else {
      val msg = s"checkstyle aborted with code $exit"
      T.log.error(msg)
      throw new RuntimeException(msg)
    }

    PathRef(out)
  }

  def checkstyleClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}")
    )
  }

  def checkstyleConfig: T[PathRef] = T.source {
    checkstyleDir().path / "config.xml"
  }

  def checkstyleDir: T[PathRef] = T.source {
    millSourcePath / "checkstyle"
  }

  def checkstyleFormat: T[String] = T {
    "plain"
  }

  def checkstyleOptions: T[Seq[String]] = T {
    if (isScala) Seq("-x", ".*[\\.]scala") else Seq.empty
  }

  def checkstyleOutput: T[String] = T {
    val fmt = checkstyleFormat()
    val ext = if (fmt == "plain") "txt" else fmt
    s"report.$ext"
  }

  def checkstyleThrow: T[Boolean] = T {
    true
  }

  def checkstyleVersion: T[String]

  private def isScala = this.isInstanceOf[ScalaModule]
}
