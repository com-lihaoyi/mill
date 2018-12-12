package mill.util

import upickle.default.{ReadWriter => RW}

trait JsonFormatters extends mill.api.JsonFormatters{
  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
}
object JsonFormatters extends JsonFormatters
