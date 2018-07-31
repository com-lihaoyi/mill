package mill.scalalib
import mill.util.JsonFormatters._
import upickle.default.{macroRW, ReadWriter => RW}

import CrossVersion._

case class Dep(dep: coursier.Dependency, cross: CrossVersion, force: Boolean) {
  def artifactName(binaryVersion: String, fullVersion: String, platformSuffix: String) = {
    val suffix = cross.suffixString(binaryVersion, fullVersion, platformSuffix)
    dep.module.name + suffix
  }
  def configure(attributes: coursier.Attributes): Dep = copy(dep = dep.copy(attributes = attributes))
  def forceVersion(): Dep = copy(force = true)
  def exclude(exclusions: (String, String)*) = copy(dep = dep.copy(exclusions = dep.exclusions ++ exclusions))
  def excludeOrg(organizations: String*): Dep = exclude(organizations.map(_ -> "*"): _*)
  def excludeName(names: String*): Dep = exclude(names.map("*" -> _): _*)
  def toDependency(binaryVersion: String, fullVersion: String, platformSuffix: String) =
    dep.copy(module = dep.module.copy(name = artifactName(binaryVersion, fullVersion, platformSuffix)))
  def withConfiguration(configuration: String): Dep = copy(dep = dep.copy(configuration = configuration))
}

object Dep {

  val DefaultConfiguration = "default(compile)"

  implicit def parse(signature: String): Dep = {
    val parts = signature.split(';')
    val module = parts.head
    val attributes = parts.tail.foldLeft(coursier.Attributes()) { (as, s) =>
      s.split('=') match {
        case Array("classifier", v) => as.copy(classifier = v)
        case Array(k, v) => throw new Exception(s"Unrecognized attribute: [$s]")
        case _ => throw new Exception(s"Unable to parse attribute specifier: [$s]")
      }
    }
    (module.split(':') match {
      case Array(a, b, c) => Dep(a, b, c, cross = empty(platformed = false))
      case Array(a, b, "", c) => Dep(a, b, c, cross = empty(platformed = true))
      case Array(a, "", b, c) => Dep(a, b, c, cross = Binary(platformed = false))
      case Array(a, "", b, "", c) => Dep(a, b, c, cross = Binary(platformed = true))
      case Array(a, "", "", b, c) => Dep(a, b, c, cross = Full(platformed = false))
      case Array(a, "", "", b, "", c) => Dep(a, b, c, cross = Full(platformed = true))
      case _ => throw new Exception(s"Unable to parse signature: [$signature]")
    }).configure(attributes = attributes)
  }
  def apply(org: String, name: String, version: String, cross: CrossVersion, force: Boolean = false): Dep = {
    apply(coursier.Dependency(coursier.Module(org, name), version, DefaultConfiguration), cross, force)
  }
  implicit def rw: RW[Dep] = macroRW
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
    this match {
      case cross: Constant =>
        s"${firstSuffix}${cross.value}"
      case cross: Binary =>
        s"${firstSuffix}_${binaryVersion}"
      case cross: Full =>
        s"${firstSuffix}_${fullVersion}"
    }
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

  def empty(platformed: Boolean) = Constant(value = "", platformed)

  implicit def rw: RW[CrossVersion] = RW.merge(Constant.rw, Binary.rw, Full.rw)
}
