package mill.scalalib.api

import mill.api.Loose.Agg
import mill.api.{PathRef, BuildProblemReporter}
import mill.api.JsonFormatters._



object ZincWorkerApi{
  type Ctx = mill.api.Ctx.Dest with mill.api.Ctx.Log with mill.api.Ctx.Home
}
trait ZincWorkerApi {
  /** Compile a Java-only project */
  def compileJava(upstreamCompileOutput: Seq[CompilationResult],
                  sources: Agg[os.Path],
                  compileClasspath: Agg[os.Path],
                  javacOptions: Seq[String],
                  reporter: Option[BuildProblemReporter])
                 (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult]

  /** Compile a mixed Scala/Java or Scala-only project */
  def compileMixed(upstreamCompileOutput: Seq[CompilationResult],
                   sources: Agg[os.Path],
                   compileClasspath: Agg[os.Path],
                   javacOptions: Seq[String],
                   scalaVersion: String,
                   scalaOrganization: String,
                   scalacOptions: Seq[String],
                   compilerClasspath: Agg[os.Path],
                   scalacPluginClasspath: Agg[os.Path],
                   reporter: Option[BuildProblemReporter])
                  (implicit ctx: ZincWorkerApi.Ctx): mill.api.Result[CompilationResult]

  def discoverMainClasses(compilationResult: CompilationResult)
                         (implicit ctx: ZincWorkerApi.Ctx): Seq[String]

  def docJar(scalaVersion: String,
             scalaOrganization: String,
             compilerClasspath: Agg[os.Path],
             scalacPluginClasspath: Agg[os.Path],
             args: Seq[String])
            (implicit ctx: ZincWorkerApi.Ctx): Boolean
}


object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] = upickle.default.macroRW
}

// analysisFile is represented by os.Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: os.Path, classes: PathRef)

object Util {
  def isDotty(scalaVersion: String) =
    scalaVersion.startsWith("0.") ||
    scalaVersion.startsWith("3.")

  // eg, grepJar(classPath, name = "scala-library", versionPrefix = "2.13.")
  // return first path in `classPath` that match:
  // **/scala-library-2.13.*.jar or
  // **/2.13.*/jars/scala-library.jar
  def grepJar(classPath: Agg[os.Path], name: String, versionPrefix: String, sources: Boolean = false) = {
    val suffix = if (sources) "-sources.jar" else ".jar"
    lazy val dir = if (sources) "srcs" else "jars"

    def mavenStyleMatch(fname: String) =
      fname.startsWith(s"$name-$versionPrefix") && fname.endsWith(suffix)

    def ivyStyleMatch(p: os.Path) = {
      val fname = s"$name$suffix"
      p.segments.toSeq match {
        case _ :+ v :+ `dir` :+ `fname` if v.startsWith(versionPrefix) => true
        case _ => false
      }
    }

    classPath.iterator
      .find(p => mavenStyleMatch(p.last) || ivyStyleMatch(p))
      .getOrElse(throw new Exception(s"Cannot find **/$name-$versionPrefix*$suffix or **/$versionPrefix*/$dir/$name$suffix in ${classPath.iterator.mkString("[", ", ", "]")}"))
  }

  val PartialVersion = raw"""(\d+)\.(\d+)\.*""".r
  val ReleaseVersion = raw"""(\d+)\.(\d+)\.(\d+)""".r
  val MinorSnapshotVersion = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r
  val DottyVersion = raw"""(0|3)\.(\d+)\.(\d+).*""".r
  val DottyNightlyVersion = raw"""(0|3)\.(\d+)\.(\d+)-bin-(.*)-NIGHTLY""".r
  val TypelevelVersion = raw"""(\d+)\.(\d+)\.(\d+)-bin-typelevel.*""".r


  def scalaBinaryVersion(scalaVersion: String) = scalaVersion match {
      case ReleaseVersion(major, minor, _) => s"$major.$minor"
      case MinorSnapshotVersion(major, minor, _) => s"$major.$minor"
      case DottyVersion("0", minor, _) => s"0.$minor"
      case TypelevelVersion(major, minor, _) => s"$major.$minor"
      case _ => scalaVersion
  }

  private val ScalaJSFullVersion = """^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$""".r

  def scalaJSBinaryVersion(scalaJSVersion: String) = scalaJSVersion match {
      case _ if scalaJSVersion.startsWith("0.6.") =>
        "0.6"
      case ScalaJSFullVersion(major, minor, patch, suffix) =>
        if (suffix != null && minor == "0" && patch == "0")
          s"$major.$minor$suffix"
        else
          major
  }

  def scalaJSWorkerVersion(scalaJSVersion: String) = scalaJSVersion match {
    case _ if scalaJSVersion.startsWith("0.6.") =>
      "0.6"
    case ScalaJSFullVersion(major, _, _, _) =>
      major
  }

  private val ScalaNativeFullVersion = """^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$""".r

  def scalaNativeBinaryVersion(version: String) = version match {
    case ScalaNativeFullVersion(major, minor, patch, suffix) =>
      if (suffix != null && patch == "0")
        version
      else
        s"$major.$minor"
  }

  def scalaNativeWorkerVersion(version: String) = version match {
    case ScalaNativeFullVersion(major, minor, _, _) =>
      s"$major.$minor"
  }

  /* Starting from Scala.js 0.6.29 and in 1.x, test artifacts must depend on
   * scalajs-test-bridge instead of scalajs-test-interface.
   */
  def scalaJSUsesTestBridge(scalaJSVersion: String): Boolean = scalaJSVersion match {
      case ScalaJSFullVersion("0", "6", patch, _) => patch.toInt >= 29
      case _ => true
  }

  /** @return true if the compiler bridge can be downloaded as an already compiled jar */
  def isBinaryBridgeAvailable(scalaVersion: String) = scalaVersion match {
      case DottyNightlyVersion(major, minor, _, _) => major.toInt > 0 || minor.toInt >= 14 // 0.14.0-bin or more (not 0.13.0-bin)
      case DottyVersion(major, minor, _) => major.toInt > 0 || minor.toInt >= 13 // 0.13.0-RC1 or more
      case _ => false
  }
}
