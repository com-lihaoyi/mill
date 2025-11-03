package mill.internal

import scala.reflect.NameTransformer.encode
import mill.api.Result
import mill.api.ModuleCtx.HeaderData

private[mill] object Util {

  val alphaKeywords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "forSome",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "try",
    "true",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "_",
    "macro"
  )

  def backtickWrap(s: String): String = s match {
    case s"`$_`" => s
    case _ => if (encode(s) == s && !alphaKeywords.contains(s)) s
      else "`" + s + "`"
  }

  private[mill] def parseHeaderData(scriptFile: os.Path): Result[HeaderData] = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(scriptFile)) ""
      else mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
    }

    def relativePath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)

    parseYaml(relativePath.toString, headerData).flatMap { parsed =>
      try Result.Success(upickle.read[HeaderData](parsed))
      catch {
        case e: upickle.core.TraceVisitor.TraceException =>
          Result.Failure(
            s"Failed de-serializing config key ${e.jsonPath} in $relativePath: ${e.getCause.getMessage}"
          )
      }
    }
  }

  def parseYaml(fileName: String, headerData: String): Result[ujson.Value] =
    try Result.Success {
        import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
        val loaded = new Load(LoadSettings.builder().build()).loadFromString(headerData)

        // recursively convert java data structure to ujson.Value
        def rec(x: Any): ujson.Value = {
          x match {
            case d: java.util.Date => ujson.Str(d.toString)
            case s: String => ujson.Str(s)
            case d: Double => ujson.Num(d)
            case d: Int => ujson.Num(d)
            case d: Long => ujson.Num(d)
            case true => ujson.True
            case false => ujson.False
            case null => ujson.Null
            case m: java.util.Map[Object, Object] =>
              import scala.jdk.CollectionConverters._
              val scalaMap = m.asScala
              ujson.Obj.from(scalaMap.map { case (k, v) => (k.toString, rec(v)) })
            case l: java.util.List[Object] =>
              import scala.jdk.CollectionConverters._
              val scalaList: collection.Seq[Object] = l.asScala
              ujson.Arr.from(scalaList.map(rec))
          }
        }

        // Treat a top-level `null` as an empty object, so that an empty YAML header
        // block is treated gracefully rather than blowing up with a NPE
        rec(loaded) match {
          case ujson.Null => ujson.Obj()
          case v => v
        }
      }
    catch {
      case e: org.snakeyaml.engine.v2.exceptions.ParserException =>
        Result.Failure(s"Failed de-serializing build header in $fileName: " + e.getMessage)
    }

  def validateBuildHeaderKeys(
      buildOverridesKeys: Set[String],
      allTaskNames: Set[String],
      relativeScriptFilePath: os.SubPath
  ) = {
    val invalidBuildOverrides = buildOverridesKeys
      .filter(!allTaskNames.contains(_))
      .filter(!_.contains('-'))

    if (invalidBuildOverrides.nonEmpty) {
      val pretty = invalidBuildOverrides.map(pprint.Util.literalize(_)).mkString(",")
      throw new Exception(
        s"invalid build config in `$relativeScriptFilePath`: key $pretty does not override any task"
      )
    }
  }
}
