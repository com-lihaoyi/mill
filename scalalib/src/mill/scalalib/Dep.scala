package mill.scalalib
import mill.util.JsonFormatters._
import upickle.default.{macroRW, ReadWriter => RW}
sealed trait Dep
object Dep {

  implicit def parse(signature: String) = {
    signature.split(':') match {
      case Array(a, b, c)         => Dep.Java(a, b, c)
      case Array(a, "", b, c)     => Dep.Scala(a, b, c)
      case Array(a, "", "", b, c) => Dep.Point(a, b, c)
      case _                      => throw new Exception(s"Unable to parse signature: [$signature]")
    }
  }
  def apply(org: String, name: String, version: String): Dep = {
    this(coursier.Dependency(coursier.Module(org, name), version))
  }
  case class Java(dep: coursier.Dependency) extends Dep
  object Java {
    implicit def rw: RW[Java] = macroRW
    def apply(org: String, name: String, version: String): Dep = {
      Java(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  implicit def default(dep: coursier.Dependency): Dep = new Java(dep)
  def apply(dep: coursier.Dependency) = Scala(dep)
  case class Scala(dep: coursier.Dependency) extends Dep
  object Scala {
    implicit def rw: RW[Scala] = macroRW
    def apply(org: String, name: String, version: String): Dep = {
      Scala(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  case class Point(dep: coursier.Dependency) extends Dep
  object Point {
    implicit def rw: RW[Point] = macroRW
    def apply(org: String, name: String, version: String): Dep = {
      Point(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  implicit def rw = RW.merge[Dep](Java.rw, Scala.rw, Point.rw)
}
