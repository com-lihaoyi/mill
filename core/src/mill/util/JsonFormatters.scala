package mill.util

import ammonite.ops.{Bytes, Path}
import upickle.Js
import upickle.default.{ReadWriter => RW}
object JsonFormatters extends JsonFormatters
trait JsonFormatters {
  implicit val pathReadWrite: RW[ammonite.ops.Path] = upickle.default
    .readwriter[String]
    .bimap[ammonite.ops.Path](
      _.toString,
      Path(_)
    )

  implicit val bytesReadWrite: RW[Bytes] = upickle.default
    .readwriter[String]
    .bimap(
      o => javax.xml.bind.DatatypeConverter.printBase64Binary(o.array),
      str => new Bytes(javax.xml.bind.DatatypeConverter.parseBase64Binary(str))
    )

  implicit lazy val crFormat: RW[ammonite.ops.CommandResult] =
    upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.Dependency] = upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursier.Attributes] =
    upickle.default.macroRW
  implicit val stackTraceRW = upickle.default
    .readwriter[Js.Obj]
    .bimap[StackTraceElement](
      ste =>
        Js.Obj(
          "declaringClass" -> Js.Str(ste.getClassName),
          "methodName" -> Js.Str(ste.getMethodName),
          "fileName" -> Js.Str(ste.getFileName),
          "lineNumber" -> Js.Num(ste.getLineNumber)
      ), {
        case json: Js.Obj =>
          new StackTraceElement(
            json("declaringClass").str.toString,
            json("methodName").str.toString,
            json("fileName").str.toString,
            json("lineNumber").num.toInt
          )
      }
    )

}
