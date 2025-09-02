package mill.api

import mill.api.daemon.internal.Severity
import os.Path
import upickle.{ReadWriter as RW, Reader, Writer}

import scala.reflect.ClassTag
import scala.util.matching.Regex

/**
 * Defines various default JSON formatters used in mill.
 */
trait JsonFormatters {

  /**
   * Additional [[mainargs.TokensReader]] instance to teach it how to read Ammonite paths
   */
  implicit object PathTokensReader extends mainargs.TokensReader.Simple[os.Path] {
    def shortName = "path"
    def read(strs: Seq[String]): Either[String, Path] =
      Right(os.Path(strs.last, BuildCtx.workspaceRoot))
  }

  implicit val pathReadWrite: RW[os.Path] = upickle.readwriter[String]
    .bimap[os.Path](
      _.toString,
      os.Path(_)
    )

  implicit val relPathRW: RW[os.RelPath] = upickle.readwriter[String]
    .bimap[os.RelPath](_.toString, os.RelPath(_))

  implicit def subPathRW: RW[os.SubPath] = JsonFormatters.Default.subPathRW

  implicit def fileRW: RW[java.io.File] = JsonFormatters.Default.fileRW

  implicit def severityRW: RW[Severity] = JsonFormatters.Default.severityRW

  implicit def resultRW[A: { Reader, Writer }]: RW[Result[A]] = JsonFormatters.Default.resultRW

  implicit val nioPathRW: RW[java.nio.file.Path] = upickle.readwriter[String]
    .bimap[java.nio.file.Path](
      _.toUri().toString(),
      s => java.nio.file.Path.of(new java.net.URI(s))
    )

  implicit val regexReadWrite: RW[Regex] = upickle.readwriter[String]
    .bimap[Regex](
      _.pattern.toString,
      _.r
    )

  implicit val bytesReadWrite: RW[geny.Bytes] = upickle.readwriter[String]
    .bimap(
      o => java.util.Base64.getEncoder.encodeToString(o.array),
      str => new geny.Bytes(java.util.Base64.getDecoder.decode(str))
    )

  implicit val crFormat: RW[os.CommandResult] = upickle.macroRW

  implicit val stackTraceRW: RW[StackTraceElement] =
    upickle.readwriter[ujson.Obj].bimap[StackTraceElement](
      ste =>
        ujson.Obj(
          "declaringClass" -> ujson.Str(ste.getClassName),
          "methodName" -> ujson.Str(ste.getMethodName),
          "fileName" -> ujson.Arr(Option(ste.getFileName).map(ujson.Str(_)).toSeq*),
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
    upickle.readwriter[String].bimap(
      _.name(),
      (s: String) =>
        implicitly[ClassTag[T]]
          .runtimeClass
          .getConstructor(classOf[String])
          .newInstance(s)
          .asInstanceOf[T]
    )

  export upickle.implicits.namedTuples.default.given
}

object JsonFormatters extends JsonFormatters {
  object Default {
    val subPathRW: RW[os.SubPath] =
      upickle.readwriter[String].bimap[os.SubPath](_.toString, os.SubPath(_))

    val fileRW: RW[java.io.File] =
      upickle.readwriter[String].bimap[java.io.File](_.toString, java.io.File(_))

    val severityRW: RW[Severity] = upickle.readwriter[String].bimap[Severity](
      {
        case mill.api.daemon.internal.Error => "error"
        case mill.api.daemon.internal.Warn => "warn"
        case mill.api.daemon.internal.Info => "info"
      },
      {
        case "error" => mill.api.daemon.internal.Error
        case "warn" => mill.api.daemon.internal.Warn
        case "info" => mill.api.daemon.internal.Info
      }
    )

    given resultRW[A: { Reader, Writer }]: RW[Result[A]] = RW.derived
  }
}
