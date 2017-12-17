package mill.scalaplugin
import mill.util.JsonFormatters._
import upickle.default.{macroRW, ReadWriter => RW}
sealed trait Dep
object Dep{
  def apply(org: String, name: String, version: String): Dep = {
    this(coursier.Dependency(coursier.Module(org, name), version))
  }
  case class Java(dep: coursier.Dependency) extends Dep
  object Java{
    implicit def rw: RW[Java] = macroRW
    def apply(org: String, name: String, version: String): Dep = {
      Java(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  implicit def default(dep: coursier.Dependency): Dep = new Java(dep)
  def apply(dep: coursier.Dependency) = Scala(dep)
  case class Scala(dep: coursier.Dependency) extends Dep
  object Scala{
    implicit def rw: RW[Scala] = macroRW
    def apply(org: String, name: String, version: String): Dep = {
      Scala(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  case class Point(dep: coursier.Dependency) extends Dep
  object Point{
    implicit def rw: RW[Point] = macroRW
    def apply(org: String, name: String, version: String): Dep = {
      Point(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  implicit def rw = RW.merge[Dep](
    Java.rw, Scala.rw, Point.rw
  )
}