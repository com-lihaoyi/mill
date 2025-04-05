package mill.api

import os.Path
import upickle.default.ReadWriter as RW

import scala.reflect.ClassTag
import scala.util.matching.Regex

object JsonFormatters extends JsonFormatters {
  private object PathTokensReader0 extends mainargs.TokensReader.Simple[os.Path] {
    def shortName = "path"
    def read(strs: Seq[String]): Either[String, Path] =
      Right(os.Path(strs.last, WorkspaceRoot.workspaceRoot))
  }
}

/**
 * Defines various default JSON formatters used in mill.
 */
trait JsonFormatters extends PathUtils {

  /**
   * Additional [[mainargs.TokensReader]] instance to teach it how to read Ammonite paths
   *
   * Should be replaced by `PathTokensReader2` but kept for binary compatibility
   */
  implicit def PathTokensReader: mainargs.TokensReader[os.Path] =
    JsonFormatters.PathTokensReader0

  def PathTokensReader2: mainargs.TokensReader.Simple[os.Path] =
    JsonFormatters.PathTokensReader0

  implicit val pathReadWrite: RW[os.Path] = upickle.default.readwriter[String]
    .bimap[os.Path](
      path => serializeEnvVariables(path),
      path => deserializeEnvVariables(path)
    )

  implicit val regexReadWrite: RW[Regex] = upickle.default.readwriter[String]
    .bimap[Regex](
      _.pattern.toString,
      _.r
    )

  implicit val bytesReadWrite: RW[geny.Bytes] = upickle.default.readwriter[String]
    .bimap(
      o => java.util.Base64.getEncoder.encodeToString(o.array),
      str => new geny.Bytes(java.util.Base64.getDecoder.decode(str))
    )

  implicit lazy val crFormat: RW[os.CommandResult] = upickle.default.macroRW

  implicit val stackTraceRW: RW[StackTraceElement] =
    upickle.default.readwriter[ujson.Obj].bimap[StackTraceElement](
      ste =>
        ujson.Obj(
          "declaringClass" -> ujson.Str(ste.getClassName),
          "methodName" -> ujson.Str(ste.getMethodName),
          "fileName" -> ujson.Arr(Option(ste.getFileName()).map(ujson.Str(_)).toSeq*),
          "lineNumber" -> ujson.Num(ste.getLineNumber)
        ),
      json =>
        new StackTraceElement(
          json("declaringClass").str.toString,
          json("methodName").str.toString,
          json("fileName").arr.headOption.map(_.str.toString).orNull,
          json("lineNumber").num.toInt
        )
    )

  implicit def enumFormat[T <: java.lang.Enum[?]: ClassTag]: RW[T] =
    upickle.default.readwriter[String].bimap(
      _.name(),
      (s: String) =>
        implicitly[ClassTag[T]]
          .runtimeClass
          .getConstructor(classOf[String])
          .newInstance(s)
          .asInstanceOf[T]
    )
}
