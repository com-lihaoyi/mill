package mill.scalalib
import mill.util.JsonFormatters._
import upickle.default.{macroRW, ReadWriter => RW}
sealed trait Dep {
  def configure(attributes: coursier.Attributes): Dep
  def exclude(exclusions: (String, String)*): Dep =
    this match {
      case dep : Dep.Java => dep.copy(dep = dep.dep.copy(exclusions = dep.dep.exclusions ++ exclusions))
      case dep : Dep.Scala => dep.copy(dep = dep.dep.copy(exclusions = dep.dep.exclusions ++ exclusions))
      case dep : Dep.Point => dep.copy(dep = dep.dep.copy(exclusions = dep.dep.exclusions ++ exclusions))
    }
  def excludeOrg(organizations: String*): Dep = exclude(organizations.map(_ -> "*"): _*)
  def excludeName(names: String*): Dep = exclude(names.map("*" -> _): _*)
  def withConfiguration(configuration: String): Dep =
    this match {
      case dep : Dep.Java => dep.copy(dep = dep.dep.copy(configuration = configuration))
      case dep : Dep.Scala => dep.copy(dep = dep.dep.copy(configuration = configuration))
      case dep : Dep.Point => dep.copy(dep = dep.dep.copy(configuration = configuration))
    }
}
object Dep{

  val DefaultConfiguration = "default(compile)"

  implicit def parse(signature: String) = {
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
      case Array(a, b, c) => Dep.Java(a, b, c, cross = false)
      case Array(a, b, "", c) => Dep.Java(a, b, c, cross = true)
      case Array(a, "", b, c) => Dep.Scala(a, b, c, cross = false)
      case Array(a, "", b, "", c) => Dep.Scala(a, b, c, cross = true)
      case Array(a, "", "", b, c) => Dep.Point(a, b, c, cross = false)
      case Array(a, "", "", b, "", c) => Dep.Point(a, b, c, cross = true)
      case _ => throw new Exception(s"Unable to parse signature: [$signature]")
    }).configure(attributes = attributes)
  }
  def apply(org: String, name: String, version: String, cross: Boolean): Dep = {
    this(coursier.Dependency(coursier.Module(org, name), version, DefaultConfiguration), cross)
  }
  case class Java(dep: coursier.Dependency, cross: Boolean) extends Dep {
    def configure(attributes: coursier.Attributes): Dep = copy(dep = dep.copy(attributes = attributes))
  }
  object Java{
    implicit def rw: RW[Java] = macroRW
    def apply(org: String, name: String, version: String, cross: Boolean): Dep = {
      Java(coursier.Dependency(coursier.Module(org, name), version, DefaultConfiguration), cross)
    }
  }
  implicit def default(dep: coursier.Dependency): Dep = new Java(dep, false)
  def apply(dep: coursier.Dependency, cross: Boolean) = Scala(dep, cross)
  case class Scala(dep: coursier.Dependency, cross: Boolean) extends Dep {
    def configure(attributes: coursier.Attributes): Dep = copy(dep = dep.copy(attributes = attributes))
  }
  object Scala{
    implicit def rw: RW[Scala] = macroRW
    def apply(org: String, name: String, version: String, cross: Boolean): Dep = {
      Scala(coursier.Dependency(coursier.Module(org, name), version, DefaultConfiguration), cross)
    }
  }
  case class Point(dep: coursier.Dependency, cross: Boolean) extends Dep {
    def configure(attributes: coursier.Attributes): Dep = copy(dep = dep.copy(attributes = attributes))
  }
  object Point{
    implicit def rw: RW[Point] = macroRW
    def apply(org: String, name: String, version: String, cross: Boolean): Dep = {
      Point(coursier.Dependency(coursier.Module(org, name), version, DefaultConfiguration), cross)
    }
  }
  implicit def rw = RW.merge[Dep](
    Java.rw, Scala.rw, Point.rw
  )
}
