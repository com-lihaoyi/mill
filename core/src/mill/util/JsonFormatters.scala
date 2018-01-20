package mill.util

import ammonite.ops.{Bytes, Path}
import upickle.Js
import upickle.default.{ReadWriter => RW}
object JsonFormatters extends JsonFormatters
trait JsonFormatters {
  implicit val pathReadWrite: RW[ammonite.ops.Path] = RW[ammonite.ops.Path](
    o => Js.Str(o.toString()),
    {case Js.Str(json) => Path(json.toString)},
  )

  implicit val bytesReadWrite: RW[Bytes] = RW[Bytes](
    o => Js.Str(javax.xml.bind.DatatypeConverter.printBase64Binary(o.array)),
    {case Js.Str(json) => new Bytes(javax.xml.bind.DatatypeConverter.parseBase64Binary(json.toString))}
  )


  implicit lazy val crFormat: RW[ammonite.ops.CommandResult] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
  implicit val stackTraceRW = upickle.default.ReadWriter[StackTraceElement](
    ste => Js.Obj(
      "declaringClass" -> Js.Str(ste.getClassName),
      "methodName" -> Js.Str(ste.getMethodName),
      "fileName" -> Js.Str(ste.getFileName),
      "lineNumber" -> Js.Num(ste.getLineNumber)
    ),
    {case json: Js.Obj =>
      new StackTraceElement(
        json("declaringClass").str.toString,
        json("methodName").str.toString,
        json("fileName").str.toString,
        json("lineNumber").num.toInt
      )
    }
  )


}
