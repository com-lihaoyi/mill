package mill.util

import ammonite.ops.{Bytes, Path}

object JsonFormatters extends JsonFormatters
trait JsonFormatters {
  implicit def readWriter[T: upickle.default.Reader: upickle.default.Writer] =
    upickle.default.ReadWriter(
      implicitly[upickle.default.Writer[T]].write,
      implicitly[upickle.default.Reader[T]].read,
    )
  implicit val pathReadWrite = upickle.default.ReadWriter[ammonite.ops.Path](
    o => upickle.Js.Str(o.toString()),
    {case upickle.Js.Str(json) => Path(json)},
  )
  implicit val pathsReadWrite = upickle.default.ReadWriter[Seq[ammonite.ops.Path]](
    o => upickle.Js.Str(upickle.default.write(o.map(pathReadWrite.write))),
    {case upickle.Js.Str(json) => upickle.default.read[Seq[upickle.Js.Value]](json).map(pathReadWrite.read)},
  )
  
  implicit val bytesReadWrite = upickle.default.ReadWriter[Bytes](
    o => upickle.Js.Str(javax.xml.bind.DatatypeConverter.printBase64Binary(o.array)),
    {case upickle.Js.Str(json) => new Bytes(javax.xml.bind.DatatypeConverter.parseBase64Binary(json))}
  )


  implicit lazy val crFormat: upickle.default.ReadWriter[ammonite.ops.CommandResult] =
    upickle.default.macroRW
  implicit lazy val modFormat: upickle.default.ReadWriter[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: upickle.default.ReadWriter[coursier.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: upickle.default.ReadWriter[coursier.Attributes] = upickle.default.macroRW
}
