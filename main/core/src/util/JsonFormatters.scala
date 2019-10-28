package mill.util

import upickle.default.{ReadWriter => RW}

trait JsonFormatters extends mill.api.JsonFormatters{
  implicit lazy val modFormat: RW[coursierapi.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursierapi.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursierapi.Attributes] = upickle.default.macroRW
  implicit lazy val orgFormat: RW[coursierapi.Organization] = upickle.default.macroRW
  implicit lazy val modNameFormat: RW[coursierapi.ModuleName] = upickle.default.macroRW
  implicit lazy val configurationFormat: RW[coursierapi.core.Configuration] = upickle.default.macroRW
  implicit lazy val typeFormat: RW[coursierapi.core.Type] = upickle.default.macroRW
  implicit lazy val classifierFormat: RW[coursierapi.core.Classifier] = upickle.default.macroRW
}
object JsonFormatters extends JsonFormatters
