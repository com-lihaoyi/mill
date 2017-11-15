package mill.scalaplugin


sealed trait Dep
object Dep{
  def apply(org: String, name: String, version: String): Dep = {
    this(coursier.Dependency(coursier.Module(org, name), version))
  }
  case class Java(dep: coursier.Dependency) extends Dep
  object Java{
    def apply(org: String, name: String, version: String): Dep = {
      Java(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  implicit def default(dep: coursier.Dependency): Dep = new Java(dep)
  def apply(dep: coursier.Dependency) = Scala(dep)
  case class Scala(dep: coursier.Dependency) extends Dep
  object Scala{
    def apply(org: String, name: String, version: String): Dep = {
      Scala(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  case class Point(dep: coursier.Dependency) extends Dep
  object Point{
    def apply(org: String, name: String, version: String): Dep = {
      Point(coursier.Dependency(coursier.Module(org, name), version))
    }
  }
  implicit def formatter = upickle.default.macroRW[Dep]
}