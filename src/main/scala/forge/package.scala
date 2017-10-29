import play.api.libs.json._
import java.nio.{file => jnio}

import ammonite.ops.Bytes

package object forge {

//  implicit object jsValueFormat extends Format[JsValue]{
//    def reads(json: JsValue) = JsSuccess(json)
//    def writes(o: jnio.Path) = JsString(o.toAbsolutePath.toString)
//  }

  implicit object pathFormat extends Format[jnio.Path]{
    def reads(json: JsValue) = json match{
      case JsString(v) => JsSuccess(jnio.Paths.get(v))
      case _ => JsError("Paths must be a String")
    }
    def writes(o: jnio.Path) = JsString(o.toAbsolutePath.toString)
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

  implicit val crFormat = Json.format[ammonite.ops.CommandResult]
  implicit val tsFormat = Json.format[Target.Subprocess.Result]
}
