package mill.util

import upickle.Js
import upickle.default.{ReadWriter => RW}
import scala.util.matching.Regex
object JsonFormatters extends JsonFormatters
trait JsonFormatters {
  implicit val pathReadWrite: RW[os.Path] = upickle.default.readwriter[String]
    .bimap[os.Path](
      _.toString,
      os.Path(_)
    )

  implicit val regexReadWrite: RW[Regex] = upickle.default.readwriter[String]
    .bimap[Regex](
      _.pattern.toString,
      _.r
    )

  implicit val bytesReadWrite: RW[os.Bytes] = upickle.default.readwriter[String]
    .bimap(
      o => java.util.Base64.getEncoder.encodeToString(o.array),
      str => new os.Bytes(java.util.Base64.getDecoder.decode(str))
    )


  implicit lazy val crFormat: RW[os.CommandResult] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
  implicit val stackTraceRW = upickle.default.readwriter[Js.Obj].bimap[StackTraceElement](
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
