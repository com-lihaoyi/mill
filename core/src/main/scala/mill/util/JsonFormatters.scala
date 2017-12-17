package mill.util

import ammonite.ops.{Bytes, Path}
import upickle.default.{ReadWriter => RW}
object JsonFormatters extends JsonFormatters
trait JsonFormatters {
  implicit val pathReadWrite: RW[ammonite.ops.Path] = RW[ammonite.ops.Path](
    o => upickle.Js.Str(o.toString()),
    {case upickle.Js.Str(json) => Path(json.toString)},
  )

  implicit val bytesReadWrite: RW[Bytes] = RW[Bytes](
    o => upickle.Js.Str(javax.xml.bind.DatatypeConverter.printBase64Binary(o.array)),
    {case upickle.Js.Str(json) => new Bytes(javax.xml.bind.DatatypeConverter.parseBase64Binary(json.toString))}
  )


  implicit lazy val crFormat: RW[ammonite.ops.CommandResult] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
}
