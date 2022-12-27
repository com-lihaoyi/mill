package mill.scalalib.api

import mill.api.Loose.Agg
import mill.api.PathRef

trait ZincWorkerUtil {

  def isDotty(scalaVersion: String) = scalaVersion.startsWith("0.")
  def isScala3(scalaVersion: String) = scalaVersion.startsWith("3.")
  def isScala3Milestone(scalaVersion: String) = scalaVersion.startsWith("3.0.0-M")
  def isDottyOrScala3(scalaVersion: String) = isDotty(scalaVersion) || isScala3(scalaVersion)

  // eg, grepJar(classPath, name = "scala-library", versionPrefix = "2.13.")
  // return first path in `classPath` that match:
  // **/scala-library-2.13.*.jar or
  // **/2.13.*/jars/scala-library.jar
  def grepJar(
      classPath: Agg[PathRef],
      name: String,
      versionPrefix: String,
      sources: Boolean = false
  ): PathRef = {
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
      .find(pathRef => mavenStyleMatch(pathRef.path.last) || ivyStyleMatch(pathRef.path))
      .getOrElse(throw new Exception(
        s"Cannot find **/$name-$versionPrefix*$suffix or **/$versionPrefix*/$dir/$name$suffix in ${classPath.iterator.mkString("[", ", ", "]")}"
      ))
  }

  val PartialVersion = raw"""(\d+)\.(\d+)\.*""".r
  val ReleaseVersion = raw"""(\d+)\.(\d+)\.(\d+)""".r
  val MinorSnapshotVersion = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r
  val DottyVersion = raw"""0\.(\d+)\.(\d+).*""".r
  val Scala3EarlyVersion = raw"""3\.0\.0-(\w+).*""".r
  val Scala3Version = raw"""3\.(\d+)\.(\d+).*""".r
  val DottyNightlyVersion = raw"""(0|3)\.(\d+)\.(\d+)-bin-(.*)-NIGHTLY""".r
  val NightlyVersion = raw"""(\d+)\.(\d+)\.(\d+)-bin-[a-f0-9]*""".r
  val TypelevelVersion = raw"""(\d+)\.(\d+)\.(\d+)-bin-typelevel.*""".r

  def scalaBinaryVersion(scalaVersion: String) = scalaVersion match {
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

  def scalaJSBinaryVersion(scalaJSVersion: String) = scalaJSVersion match {
    case _ if scalaJSVersion.startsWith("0.6.") =>
      throw new Exception("Scala.js 0.6 is not supported")
    case ScalaJSFullVersion(major, minor, patch, suffix) =>
      if (suffix != null && minor == "0" && patch == "0")
        s"$major.$minor$suffix"
      else
        major
  }

  def scalaJSWorkerVersion(scalaJSVersion: String) = scalaJSVersion match {
    case _ if scalaJSVersion.startsWith("0.6.") =>
      throw new Exception("Scala.js 0.6 is not supported")
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
  @deprecated("No longer used", "Mill after 0.11.0-M0")
  def scalaJSUsesTestBridge(scalaJSVersion: String): Boolean = scalaJSVersion match {
    case ScalaJSFullVersion("0", "6", patch, _) => patch.toInt >= 29
    case _ => true
  }

  /** @return true if the compiler bridge can be downloaded as an already compiled jar */
  def isBinaryBridgeAvailable(scalaVersion: String) =
    scalaVersion match {
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
    val versionParts = version.split('.').map(_.toIntOption).takeWhile(_.isDefined).map(_.get)
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

object ZincWorkerUtil extends ZincWorkerUtil
