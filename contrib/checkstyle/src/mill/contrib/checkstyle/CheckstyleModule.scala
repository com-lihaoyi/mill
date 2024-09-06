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
      T.log.error(s"checkstyle found $exit problem(s)")
      T.log.error(s"  $out")
      if (thrw) {
        throw new RuntimeException("checkstyle failed")
      }
    } else {
      T.log.error(s"checkstyle exited with code $exit")
      throw new RuntimeException("checkstyle crashed")
    }

    PathRef(out)
  }

  def checkstyleClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}")
    )
  }

  def checkstyleConfig: T[PathRef] = T.source {
    millSourcePath / "checkstyle-config.xml"
  }

  def checkstyleFormat: T[CheckstyleModule.Format] = T {
    CheckstyleModule.Format.plain
  }

  def checkstyleOptions: T[Seq[String]] = T {
    if (isScala) Seq("-x", ".*[\\.]scala") else Seq.empty
  }

  def checkstyleOutput: T[String] = T {
    val fmt = checkstyleFormat()
    val ext = if (fmt == CheckstyleModule.Format.plain) "txt" else fmt
    s"report.$ext"
  }

  def checkstyleThrow: T[Boolean] = T {
    true
  }

  def checkstyleVersion: T[String]

  private def isScala = this.isInstanceOf[ScalaModule]
}

object CheckstyleModule {

  import upickle.default._

  implicit val formatRW: ReadWriter[Format] =
    implicitly[ReadWriter[String]].bimap(_.toString(), Format.withName(_))

  type Format = Format.Value

  object Format extends Enumeration {
    val plain, sarif, xml = Value
  }
}
