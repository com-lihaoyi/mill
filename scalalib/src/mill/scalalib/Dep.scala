package mill.scalalib

import upickle.default.{macroRW, ReadWriter as RW}
import mill.scalalib.CrossVersion.*
import coursier.core.{Configuration, Dependency, MinimizedExclusions}
import mill.scalalib.api.{Versions, JvmWorkerUtil}
import scala.annotation.unused

case class Dep(dep: coursier.Dependency, cross: CrossVersion, force: Boolean) {
  require(
    !dep.module.name.value.contains("/") &&
      !dep.module.organization.value.contains("/") &&
      !dep.version.contains("/"),
    "Dependency coordinates must not contain `/`s"
  )

  def artifactName(binaryVersion: String, fullVersion: String, platformSuffix: String): String = {
    val suffix = cross.suffixString(binaryVersion, fullVersion, platformSuffix)
    dep.module.name.value + suffix
  }
  def configure(attributes: coursier.Attributes): Dep = copy(dep = dep.withAttributes(attributes))
  def forceVersion(): Dep = copy(force = true)
  def exclude(exclusions: (String, String)*): Dep = copy(
    dep = dep.withExclusions(
      dep.exclusions() ++
        exclusions.map { case (k, v) => (coursier.Organization(k), coursier.ModuleName(v)) }
    )
  )
  def excludeOrg(organizations: String*): Dep = exclude(organizations.map(_ -> "*")*)
  def excludeName(names: String*): Dep = exclude(names.map("*" -> _)*)
  def toDependency(binaryVersion: String, fullVersion: String, platformSuffix: String): Dependency =
    dep.withModule(
      dep.module.withName(
        coursier.ModuleName(artifactName(binaryVersion, fullVersion, platformSuffix))
      )
    )
  def bindDep(binaryVersion: String, fullVersion: String, platformSuffix: String): BoundDep =
    BoundDep(
      dep.withModule(
        dep.module.withName(
          coursier.ModuleName(artifactName(binaryVersion, fullVersion, platformSuffix))
        )
      ),
      force
    )

  def withConfiguration(configuration: String): Dep = copy(
    dep = dep.withConfiguration(coursier.core.Configuration(configuration))
  )
  def optional(optional: Boolean = true): Dep = copy(
    dep = dep.withOptional(optional)
  )

  def organization = dep.module.organization.value
  def name = dep.module.name.value
  def version = dep.versionConstraint.asString

  /**
   * If scalaVersion is a Dotty version, replace the cross-version suffix
   * by the Scala 2.x version that the Dotty version is retro-compatible with,
   * otherwise do nothing.
   *
   * This setting is useful when your build contains dependencies that have only
   * been published with Scala 2.x, if you have:
   * {{{
   * def ivyDeps = Seq(ivy"a::b:c")
   * }}}
   * you can replace it by:
   * {{{
   * def ivyDeps = Seq(ivy"a::b:c".withDottyCompat(scalaVersion()))
   * }}}
   * This will have no effect when compiling with Scala 2.x, but when compiling
   * with Dotty this will change the cross-version to a Scala 2.x one. This
   * works because Dotty is currently retro-compatible with Scala 2.x.
   */
  def withDottyCompat(scalaVersion: String): Dep =
    cross match {
      case cross: Binary if JvmWorkerUtil.isDottyOrScala3(scalaVersion) =>
        val compatSuffix =
          scalaVersion match {
            case JvmWorkerUtil.Scala3Version(_, _) | JvmWorkerUtil.Scala3EarlyVersion(_) =>
              "_2.13"
            case JvmWorkerUtil.DottyVersion(minor, patch) =>
              if (minor.toInt > 18 || minor.toInt == 18 && patch.toInt >= 1)
                "_2.13"
              else
                "_2.12"
            case _ =>
              ""
          }
        if (compatSuffix.nonEmpty)
          copy(cross = Constant(value = compatSuffix, platformed = cross.platformed))
        else
          this
      case _ =>
        this
    }
}

object Dep {

  val DefaultConfiguration: Configuration = coursier.core.Configuration("default(compile)")

  implicit def parse(signature: String): Dep = {
    val parts = signature.split(';')
    val module = parts.head
    var exclusions = Seq.empty[(String, String)]
    val attributes = parts.tail.foldLeft(coursier.Attributes()) { (as, s) =>
      s.split('=') match {
        case Array("classifier", v) => as.withClassifier(coursier.Classifier(v))
        case Array("type", v) => as.withType(coursier.Type(v))
        case Array("exclude", s"${org}:${name}") => exclusions ++= Seq((org, name)); as
        case Array(k, v) => throw new Exception(s"Unrecognized attribute: [$s]")
        case _ => throw new Exception(s"Unable to parse attribute specifier: [$s]")
      }
    }

    (module.split(':') match {
      case Array(a, b) => Dep(a, b, "", cross = empty(platformed = false))
      case Array(a, "", b) => Dep(a, b, "", cross = Binary(platformed = false))
      case Array(a, b, c) => Dep(a, b, c, cross = empty(platformed = false))
      case Array(a, b, "", c) => Dep(a, b, c, cross = empty(platformed = true))
      case Array(a, "", b, c) => Dep(a, b, c, cross = Binary(platformed = false))
      case Array(a, "", b, "", c) => Dep(a, b, c, cross = Binary(platformed = true))
      case Array(a, "", "", b, c) => Dep(a, b, c, cross = Full(platformed = false))
      case Array(a, "", "", b, "", c) => Dep(a, b, c, cross = Full(platformed = true))
      case _ => throw new Exception(s"Unable to parse signature: [$signature]")
    })
      .exclude(exclusions.sorted*)
      .configure(attributes = attributes)
  }

  @unused private implicit val depFormat: RW[Dependency] = mill.scalalib.JsonFormatters.depFormat

  def unparse(dep: Dep): Option[String] = {
    val org = dep.dep.module.organization.value
    val mod = dep.dep.module.name.value
    val ver = dep.dep.version

    val classifierAttr = dep.dep.attributes.classifier.value match {
      case "" => ""
      case s => s";classifier=$s"
    }

    val typeAttr = dep.dep.attributes.`type`.value match {
      case "" => ""
      case s => s";type=$s"
    }

    val excludeAttr =
      dep.dep.exclusions().toSeq.sorted.map(e => s";exclude=${e._1.value}:${e._2.value}").mkString

    val attrs = classifierAttr + typeAttr + excludeAttr

    val prospective = dep.cross match {
      case CrossVersion.Constant("", false) => Some(s"$org:$mod:$ver$attrs")
      case CrossVersion.Constant("", true) => Some(s"$org:$mod::$ver$attrs")
      case CrossVersion.Binary(false) => Some(s"$org::$mod:$ver$attrs")
      case CrossVersion.Binary(true) => Some(s"$org::$mod::$ver$attrs")
      case CrossVersion.Full(false) => Some(s"$org:::$mod:$ver$attrs")
      case CrossVersion.Full(true) => Some(s"$org:::$mod::$ver$attrs")
      case CrossVersion.Constant(v, _) => None
    }

    prospective.filter(parse(_) == dep)
  }

  private val rw0: RW[Dep] = macroRW

  // Use literal JSON strings for common cases so that files
  // containing serialized dependencies can be easier to skim
  implicit val rw: RW[Dep] = upickle.default.readwriter[ujson.Value].bimap[Dep](
    (dep: Dep) =>
      unparse(dep) match {
        case Some(s) => ujson.Str(s)
        case None => upickle.default.writeJs[Dep](dep)(using rw0)
      },
    {
      case s: ujson.Str => parse(s.value)
      case v: ujson.Value => upickle.default.read[Dep](v)(using rw0)
    }
  )

  def apply(
      org: String,
      name: String,
      version: String,
      cross: CrossVersion,
      force: Boolean = false
  ): Dep = {
    apply(
      coursier.Dependency(
        coursier.Module(coursier.Organization(org), coursier.ModuleName(name)),
        version
      ),
      cross,
      force
    )
  }

  /**
   * Convenience to access Mill modules as dependencies, e.g. to load the into worker classpaths.
   *
   * @param artifactName   The module artifact name
   * @param artifactSuffix The artifact suffix typically representing the Scala version.
   *                       Defaults to the Scala binary platform Mill runs on.
   */
  private[mill] def millProjectModule(artifactName: String, artifactSuffix: String = "_3"): Dep = {
    // we don't use `ivy` string context here to avoid a cyclic dependency
    val dep = s"com.lihaoyi:${artifactName}${artifactSuffix}:${Versions.millVersion}"
    Dep.parse(dep)
  }

}

sealed trait CrossVersion {

  /** If true, the cross-version suffix should start with a platform suffix if it exists */
  def platformed: Boolean

  def isBinary: Boolean =
    this.isInstanceOf[Binary]
  def isConstant: Boolean =
    this.isInstanceOf[Constant]
  def isFull: Boolean =
    this.isInstanceOf[Full]

  /** The string that should be appended to the module name to get the artifact name */
  def suffixString(binaryVersion: String, fullVersion: String, platformSuffix: String): String = {
    val firstSuffix = if (platformed) platformSuffix else ""
    val suffix = this match {
      case cross: Constant =>
        s"${firstSuffix}${cross.value}"
      case cross: Binary =>
        s"${firstSuffix}_${binaryVersion}"
      case cross: Full =>
        s"${firstSuffix}_${fullVersion}"
    }
    require(!suffix.contains("/"), "Artifact suffix must not contain `/`s")
    suffix
  }
}
object CrossVersion {
  case class Constant(value: String, platformed: Boolean) extends CrossVersion
  object Constant {
    implicit def rw: RW[Constant] = macroRW
  }
  case class Binary(platformed: Boolean) extends CrossVersion
  object Binary {
    implicit def rw: RW[Binary] = macroRW
  }
  case class Full(platformed: Boolean) extends CrossVersion
  object Full {
    implicit def rw: RW[Full] = macroRW
  }

  def empty(platformed: Boolean): Constant = Constant(value = "", platformed)

  implicit def rw: RW[CrossVersion] = RW.merge(Constant.rw, Binary.rw, Full.rw)
}

/**
 * Same as [[Dep]] but with already bound cross and platform settings.
 */
case class BoundDep(
    dep: coursier.Dependency,
    force: Boolean
) {
  def organization = dep.module.organization.value
  def name = dep.module.name.value
  def version = dep.version

  def toDep: Dep = Dep(dep = dep, cross = CrossVersion.empty(false), force = force)

  def exclude(exclusions: (String, String)*): BoundDep = copy(
    dep = dep.withMinimizedExclusions(
      dep.minimizedExclusions.join(MinimizedExclusions(
        exclusions.toSet.map { case (k, v) => (coursier.Organization(k), coursier.ModuleName(v)) }
      ))
    )
  )
}

object BoundDep {
  @unused private implicit val depFormat: RW[Dependency] = mill.scalalib.JsonFormatters.depFormat
  private val jsonify0: upickle.default.ReadWriter[BoundDep] = upickle.default.macroRW

  // Use literal JSON strings for common cases so that files
  // containing serialized dependencies can be easier to skim
  //
  // `BoundDep` is basically a `Dep` with `cross=CrossVersion.Constant("", false)`,
  // so we can re-use most of `Dep`'s serialization logic
  implicit val jsonify: upickle.default.ReadWriter[BoundDep] =
    upickle.default.readwriter[ujson.Value].bimap[BoundDep](
      bdep => {
        Dep.unparse(Dep(bdep.dep, CrossVersion.Constant("", false), bdep.force)) match {
          case None => upickle.default.writeJs(bdep)(using jsonify0)
          case Some(s) => ujson.Str(s)
        }
      },
      {
        case ujson.Str(s) =>
          val dep = Dep.parse(s)
          BoundDep(dep.dep, dep.force)
        case v => upickle.default.read[BoundDep](v)(using jsonify0)
      }
    )
}
