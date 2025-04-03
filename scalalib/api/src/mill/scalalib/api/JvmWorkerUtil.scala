package mill.scalalib.api

import mill.api.PathRef
import scala.util.matching.Regex

object JvmWorkerUtil {

  def isDotty(scalaVersion: String): Boolean = scalaVersion.startsWith("0.")
  def isScala3(scalaVersion: String): Boolean = scalaVersion.startsWith("3.")
  def isScala3Milestone(scalaVersion: String): Boolean = scalaVersion.startsWith("3.0.0-M")
  def isDottyOrScala3(scalaVersion: String): Boolean =
    isDotty(scalaVersion) || isScala3(scalaVersion)

  // eg, grepJar(classPath, name = "scala-library", versionPrefix = "2.13.")
  // return first path in `classPath` that match:
  // **/scala-library-2.13.*.jar or
  // **/2.13.*/jars/scala-library.jar
  def grepJar(
      classPath: Seq[PathRef],
      name: String,
      versionPrefix: String,
      sources: Boolean = false
  ): PathRef = {
    val suffix = if (sources) "-sources.jar" else ".jar"
    lazy val dir = if (sources) "srcs" else "jars"

    def mavenStyleMatch(fname: String): Boolean =
      fname.startsWith(s"$name-$versionPrefix") && fname.endsWith(suffix)

    def ivyStyleMatch(p: os.Path): Boolean = {
      val fname = s"$name$suffix"
      p.segments.toSeq match {
        case _ :+ v :+ `dir` :+ `fname` if v.startsWith(versionPrefix) => true
        case _ => false
      }
    }

    classPath.iterator
      .find(pathRef => mavenStyleMatch(pathRef.path.last) || ivyStyleMatch(pathRef.path))
      .getOrElse(throw new Exception(
        s"Cannot find **/$name-$versionPrefix*$suffix or **/$versionPrefix*/$dir/$name$suffix in ${classPath.iterator.mkString("[", ", ", "]")}"
      ))
  }

  val PartialVersion: Regex = raw"""(\d+)\.(\d+)\.*""".r
  val ReleaseVersion: Regex = raw"""(\d+)\.(\d+)\.(\d+)""".r
  val MinorSnapshotVersion: Regex = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r
  val DottyVersion: Regex = raw"""0\.(\d+)\.(\d+).*""".r
  val Scala3EarlyVersion: Regex = raw"""3\.0\.0-(\w+).*""".r
  val Scala3Version: Regex = raw"""3\.(\d+)\.(\d+).*""".r
  val DottyNightlyVersion: Regex = raw"""(0|3)\.(\d+)\.(\d+)-bin-(.*)-NIGHTLY""".r
  val NightlyVersion: Regex = raw"""(\d+)\.(\d+)\.(\d+)-bin-[a-f0-9]*""".r
  val TypelevelVersion: Regex = raw"""(\d+)\.(\d+)\.(\d+)-bin-typelevel.*""".r

  def scalaBinaryVersion(scalaVersion: String): String = scalaVersion match {
    case Scala3EarlyVersion(milestone) => s"3.0.0-$milestone"
    case Scala3Version(_, _) => "3"
    case ReleaseVersion(major, minor, _) => s"$major.$minor"
    case MinorSnapshotVersion(major, minor, _) => s"$major.$minor"
    case NightlyVersion(major, minor, _) => s"$major.$minor"
    case DottyVersion(minor, _) => s"0.$minor"
    case TypelevelVersion(major, minor, _) => s"$major.$minor"
    case _ => scalaVersion
  }

  private val ScalaJSFullVersion = """^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$""".r

  def scalaJSBinaryVersion(scalaJSVersion: String): String = scalaJSVersion match {
    case _ if scalaJSVersion.startsWith("0.6.") =>
      throw new Exception("Scala.js 0.6 is not supported")
    case ScalaJSFullVersion(major, minor, patch, suffix) =>
      if (suffix != null && minor == "0" && patch == "0")
        s"$major.$minor$suffix"
      else
        major
  }

  def scalaJSWorkerVersion(scalaJSVersion: String): String = scalaJSVersion match {
    case _ if scalaJSVersion.startsWith("0.6.") =>
      throw new Exception("Scala.js 0.6 is not supported")
    case ScalaJSFullVersion(major, _, _, _) =>
      major
  }

  private val ScalaNativeFullVersion = """^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$""".r

  def scalaNativeBinaryVersion(version: String): String = version match {
    case ScalaNativeFullVersion(major, minor, patch, suffix) =>
      if (suffix != null && patch == "0")
        version
      else
        s"$major.$minor"
  }

  def scalaNativeWorkerVersion(version: String): String = version match {
    case ScalaNativeFullVersion(major, minor, _, _) =>
      s"$major.$minor"
  }

  lazy val millCompilerBridgeScalaVersions: Set[String] =
    Versions.millCompilerBridgeScalaVersions.split(",").toSet

  /** @return true if the compiler bridge can be downloaded as an already compiled jar */
  def isBinaryBridgeAvailable(scalaVersion: String): Boolean =
    if (millCompilerBridgeScalaVersions.contains(scalaVersion)) true
    else scalaVersion match {
      case DottyNightlyVersion(major, minor, _, _) =>
        major.toInt > 0 || minor.toInt >= 14 // 0.14.0-bin or more (not 0.13.0-bin)
      case DottyVersion(minor, _) => minor.toInt >= 13 // 0.13.0-RC1 or more
      case Scala3EarlyVersion(_) | Scala3Version(_, _) => true
      case _ => false
    }

  /**
   * Given a version string using a semantic versioning scheme (like x.y.z) it
   * returns all the sub-versions in it (major, minor, patch, etc.).
   * For example, matchingVersions("2.0.0") returns "2.0.0", "2.0" and "2"
   */
  def matchingVersions(version: String): Seq[String] = {
    (for (segments <- version.split('.').inits.filter(_.nonEmpty))
      yield segments.mkString(".")).to(Seq)
  }

  /**
   * Given a version string and the sequence of all the possible versions strings
   * using a semantic versioning scheme (like x.y.z) it returns all the version
   * ranges that contain `version` for all sub-version (major, minor, patch) in
   * `allVersions`.
   * For example, `versionRanges("2.0", Seq("1.0", "2.0", "3.0"))` returns versions
   * like `"1+"`, `"3-"`, `"3.0-"`, `"2+"`, `"2-"` and so on.
   */
  def versionRanges(version: String, allVersions: Seq[String]): Seq[String] = {
    import scala.math.Ordering.Implicits._
    val versionParts = version.split('.').map(_.toIntOption).takeWhile(_.isDefined).map(_.get).toSeq
    val all = allVersions.flatMap(
      _.split('.').inits
        .flatMap { l =>
          try { Some(l.map(_.toInt)) }
          catch { case _: NumberFormatException => None }
        }
        .map(_.toSeq)
    )
    val plus =
      all.filter(v => v.nonEmpty && v <= versionParts.take(v.length)).map(_.mkString(".") + "+")
    val minus =
      all.filter(v => v.nonEmpty && v >= versionParts.take(v.length)).map(_.mkString(".") + "-")
    (plus ++ minus).distinct.toSeq
  }
}
