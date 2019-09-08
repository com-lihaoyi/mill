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


  def grepJar(classPath: Agg[os.Path], name: String, version: String, sources: Boolean = false) = {
    val suffix = if (sources) "-sources" else ""
    val mavenStylePath = s"$name-$version$suffix.jar"
    val ivyStylePath = {
      val dir = if (sources) "srcs" else "jars"
      s"$version/$dir/$name$suffix.jar"
    }

    classPath
      .find(p => p.toString.endsWith(mavenStylePath) || p.toString.endsWith(ivyStylePath))
      .getOrElse(throw new Exception(s"Cannot find $mavenStylePath or $ivyStylePath in ${classPath.mkString("[", ", ", "]")}"))
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

  /** @return true if the compiler bridge can be downloaded as an already compiled jar */
  def isBinaryBridgeAvailable(scalaVersion: String) = scalaVersion match {
      case DottyNightlyVersion(major, minor, _, _) => major.toInt > 0 || minor.toInt >= 14 // 0.14.0-bin or more (not 0.13.0-bin)
      case DottyVersion(major, minor, _) => major.toInt > 0 || minor.toInt >= 13 // 0.13.0-RC1 or more
      case _ => false
  }
}
