package forge.util

import ammonite.ops.{Bytes, Path}
import play.api.libs.json._
object JsonFormatters extends JsonFormatters
trait JsonFormatters {
  implicit object pathFormat extends Format[ammonite.ops.Path]{
    def reads(json: JsValue) = json match{
      case JsString(v) => JsSuccess(Path(v))
      case _ => JsError("Paths must be a String")
    }
    def writes(o: Path) = JsString(o.toString)
  }

  implicit object bytesFormat extends Format[Bytes]{
    def reads(json: JsValue) = json match{
      case JsString(v) => JsSuccess(
        new Bytes(javax.xml.bind.DatatypeConverter.parseBase64Binary(v))
      )
      case _ => JsError("Bytes must be a String")
    }
    def writes(o: Bytes) = {
      JsString(javax.xml.bind.DatatypeConverter.printBase64Binary(o.array))
    }
  }

  implicit def EitherFormat[T: Format, V: Format] = new Format[Either[T, V]]{
    def reads(json: JsValue) = json match{
      case JsObject(struct) =>
        (struct.get("type"), struct.get("value")) match{
          case (Some(JsString("Left")), Some(v)) => implicitly[Reads[T]].reads(v).map(Left(_))
          case (Some(JsString("Right")), Some(v)) => implicitly[Reads[V]].reads(v).map(Right(_))
          case _ => JsError("Either object layout is unknown")
        }
      case _ => JsError("Either must be an Object")
    }
    def writes(o: Either[T, V]) = o match{
      case Left(v) => Json.obj("type" -> "Left", "value" -> implicitly[Writes[T]].writes(v))
      case Right(v) => Json.obj("type" -> "Right", "value" -> implicitly[Writes[V]].writes(v))
    }
  }

  implicit lazy val crFormat: Format[ammonite.ops.CommandResult] = Json.format
  implicit lazy val modFormat: Format[coursier.Module] = Json.format
  implicit lazy val depFormat: Format[coursier.Dependency]= Json.format
  implicit lazy val attrFormat: Format[coursier.Attributes] = Json.format
}
