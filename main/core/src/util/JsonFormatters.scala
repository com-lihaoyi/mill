package mill.util

import upickle.default.{ReadWriter => RW}

import scala.reflect.ClassTag

trait JsonFormatters extends mill.api.JsonFormatters{
  implicit lazy val publicationFormat: RW[coursier.core.Publication] = upickle.default.macroRW
  implicit lazy val extensionFormat: RW[coursier.core.Extension] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.Dependency]= upickle.default.macroRW
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
  implicit lazy val orgFormat: RW[coursier.Organization] = upickle.default.macroRW
  implicit lazy val modNameFormat: RW[coursier.ModuleName] = upickle.default.macroRW
  implicit lazy val configurationFormat: RW[coursier.core.Configuration] = upickle.default.macroRW
  implicit lazy val typeFormat: RW[coursier.core.Type] = upickle.default.macroRW
  implicit lazy val classifierFormat: RW[coursier.core.Classifier] = upickle.default.macroRW

  implicit def enumFormat[T <: java.lang.Enum[_]: ClassTag]: RW[T] = upickle.default.readwriter[String].bimap(
    _.name(),
    (s: String) =>
      implicitly[ClassTag[T]]
        .runtimeClass
        .getConstructor(classOf[String])
        .newInstance(s)
        .asInstanceOf[T]
  )
}
object JsonFormatters extends JsonFormatters
