package mill.scalaplugin

import play.api.libs.json._
import mill.util.JsonFormatters._
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
  implicit def formatter: Format[Dep] = new Format[Dep]{
    def writes(o: Dep) = o match{
      case Java(dep) => Json.obj("Java" -> Json.toJson(dep))
      case Scala(dep) => Json.obj("Scala" -> Json.toJson(dep))
      case Point(dep) => Json.obj("PointScala" -> Json.toJson(dep))
    }

    def reads(json: JsValue) = json match{
      case obj: JsObject =>
        obj.fields match{
          case Seq(("Java", dep)) => Json.fromJson[coursier.Dependency](dep).map(Java(_))
          case Seq(("Scala", dep)) => Json.fromJson[coursier.Dependency](dep).map(Scala(_))
          case Seq(("PointScala", dep)) => Json.fromJson[coursier.Dependency](dep).map(Point(_))
          case _ => JsError("Invalid JSON object to parse ScalaDep")
        }


      case _ => JsError("Expected JSON object to parse ScalaDep")
    }
  }
}