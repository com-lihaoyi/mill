package mill.internal

import scala.reflect.NameTransformer.encode
import mill.api.Result
import mill.api.Logger
import mill.api.ExecResult
import mill.api.Result.Failure.ExceptionInfo
import mill.api.internal.HeaderData
import mill.api.daemon.internal.ExecutionResultsApi
import scala.collection.mutable

object Util {

  private[mill] def catchUpickleAbort[T](
      path: java.nio.file.Path,
      prefix: String = ""
  )(t: => T): Result[T] = {
    try Result.Success(t)
    catch {
      case abort: upickle.core.AbortException =>
        Result.Failure(
          prefix + Option(abort.getMessage).getOrElse("YAML type mismatch"),
          path,
          abort.index
        )
    }
  }

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
    case _ =>
      if (encode(s) == s && !alphaKeywords.contains(s) && Character.isJavaIdentifierStart(s.head)) s
      else "`" + s + "`"
  }

  def getLineNumber(text: String, index: Int): String = {
    fastparse.IndexedParserInput(text).prettyIndex(index).takeWhile(_ != ':')
  }

  def formatError(f: Result.Failure, highlight: String => String) = {
    Result.Failure
      .split(f)
      .map(f0 =>
        formatError0(f0.path, f0.index, f0.error, f0.exception, f0.tickerPrefix, highlight)
      )
      .mkString("\n")
  }

  /**
   * Format an error message in dotty style with file location, code snippet, and pointer.
   *
   * @param fileName The file name or path to display
   * @param text The full text content of the file
   * @param index The character index where the error occurred
   * @param message The error message to display
   * @return A formatted error string with location, code snippet, pointer, and message
   */
  def formatError0(
      path: java.nio.file.Path,
      index: Int,
      message: String,
      exception: Seq[ExceptionInfo],
      tickerPrefix: String,
      highlight: String => String
  ): String = {
    val exceptionSuffix =
      if (exception.nonEmpty) Some(formatException(exception, highlight)) else None

    val positionedMessage =
      if (path == null || !java.nio.file.Files.exists(path))
        "[" + highlight("error") + "] " + message
      else {
        val text = java.nio.file.Files.readString(path)
        val indexedParser = fastparse.IndexedParserInput(text.replace("//| ", "").replace("\r", ""))
        val prettyIndex = indexedParser.prettyIndex(index)
        val Array(lineNum, colNum0) = prettyIndex.split(':').map(_.toInt)

        // Get the line content
        val lines = text.linesIterator.toVector
        val lineContent = if (lineNum > 0 && lineNum <= lines.length) lines(lineNum - 1) else ""

        // Offset column by 4 if line starts with "//| " to account for stripped YAML prefix (including space)
        val colNum = if (lineContent.startsWith("//| ")) colNum0 + 4 else colNum0

        mill.constants.Util.formatError(
          mill.api.BuildCtx.workspaceRoot.toNIO.relativize(path).toString,
          lineNum,
          colNum,
          lineContent,
          message,
          s => highlight(s)
        )
      }

    val prefix = highlight(tickerPrefix)

    (Seq(prefix + positionedMessage) ++ exceptionSuffix).mkString("\n")
  }

  def formatException(exception: Seq[ExceptionInfo], highlight: String => String): String = {
    val output = mutable.Buffer.empty[fansi.Str]
    for (ExceptionInfo(clsName, msg, stack) <- exception) {
      val exCls = highlight(clsName)
      output.append(
        msg match {
          case null => fansi.Str(exCls)
          case nonNull => fansi.Str.join(Seq(exCls, ": ", nonNull))
        }
      )

      for (frame <- stack) {
        val filenameFrag: fansi.Str = frame.getFileName match {
          case null => "Unknown"
          case fileName =>
            fansi.Str(highlight(fileName), ":", highlight(frame.getLineNumber.toString))
        }

        output.append(
          fansi.Str(
            "  ",
            highlight(frame.getClassName + "." + frame.getMethodName),
            "(",
            filenameFrag,
            ")"
          )
        )
      }
    }
    fansi.Str.join(output, "\n").render
  }

  def parseHeaderData(scriptFile: os.Path): Result[HeaderData] =
    HeaderData.parseHeaderData(scriptFile)

  def isPrecompiledYamlModule(path: os.Path): Boolean = {
    parseHeaderData(path) match {
      case Result.Success(headerData) => headerData.`mill-experimental-precompiled-module`.value
      case _ => false
    }
  }

  def parseYaml0[T](
      fileName: String,
      headerData: String,
      visitor0: upickle.core.Visitor[_, T]
  ): Result[T] =
    HeaderData.parseYaml0(os.Path(fileName, mill.api.BuildCtx.workspaceRoot), headerData, visitor0)

  /**
   * Parses a config value from the YAML header data.
   * Returns the parsed value or a default on missing key. Throws on parse failure.
   */
  def parseBuildHeaderValue[T: upickle.default.Reader](
      headerData: String,
      configKey: String,
      default: T
  ): T =
    parseYaml0(
      "build header",
      headerData,
      upickle.default.reader[Map[String, ujson.Value]]
    ) match {
      case Result.Success(conf) =>
        conf.get(configKey) match {
          case Some(value) => upickle.default.read[T](value)
          case None => default
        }
      case f: Result.Failure =>
        throw new mill.api.daemon.MillException(s"Failed parsing build header: ${f.error}")
    }

  /**
   * Reads a boolean flag from the root build.mill YAML header.
   */
  def readBooleanFromBuildHeader(
      projectRoot: os.Path,
      configKey: String,
      rootBuildFileNames: Seq[String]
  ): Boolean = {
    rootBuildFileNames
      .map(name => projectRoot / name)
      .find(os.exists)
      .exists { buildFile =>
        val headerData = mill.constants.Util.readBuildHeader(buildFile.toNIO, buildFile.last)
        parseBuildHeaderValue[Boolean](headerData, configKey, default = false)
      }
  }

  def splitPreserveEOL(bytes: Array[Byte]): Seq[Array[Byte]] = {
    val out = scala.collection.mutable.ArrayBuffer[Array[Byte]]()
    var i = 0
    val n = bytes.length
    import java.util.Arrays.copyOfRange
    while (i < n) {
      val start = i

      while (i < n && bytes(i) != '\n' && bytes(i) != '\r') i += 1 // Move to end-of-line

      if (i >= n) out += copyOfRange(bytes, start, n) // Last line with no newline
      else { // Found either '\n' or '\r'
        if (bytes(i) == '\r') { // CR
          if (i + 1 < n && bytes(i + 1) == '\n') { // CRLF
            i += 2
            out += copyOfRange(bytes, start, i)
          } else { // Lone CR
            i += 1
            out += copyOfRange(bytes, start, i)
          }
        } else { // LF
          i += 1
          out += copyOfRange(bytes, start, i)
        }
      }
    }

    out.toSeq
  }

  def formatFailing(evaluated: ExecutionResultsApi): Result.Failure = {
    Result.Failure.join(
      for ((key, fs) <- evaluated.transitiveFailingApi.toSeq)
        yield {
          val keyPrefix =
            Logger.formatPrefix(evaluated.transitivePrefixesApi.getOrElse(key, Nil))

          def convertFailure(f: ExecResult.Failure[_]): Result.Failure = {
            f.failure match {
              // If there is no associated `Result.Failure`,
              // synthesize one based on the `key` and the `f.msg`
              case None => Result.Failure(error = s"$key ${f.msg}", tickerPrefix = keyPrefix)
              case Some(failure) =>
                // If there is an associated `Result.Failure` with no prefix, set the prefix to the
                // current `keyPrefix` and prefix `error` with `key`
                if (failure.tickerPrefix == "")
                  failure.copy(error = s"$key ${failure.error}", tickerPrefix = keyPrefix)
                // If there is an associated `Result.Failure` with its own prefix, preserve it
                // and chain together a new `Result.Failure` entry representing the current key
                else Result.Failure(error = s"$key", tickerPrefix = keyPrefix, next = Some(failure))
            }
          }

          fs match {
            case f: ExecResult.Failure[_] => convertFailure(f)
            case ex: ExecResult.Exception =>
              Result.Failure.fromException(
                ex.throwable,
                ex.outerStack.value.toArray,
                ex.outerStack.cutExtra
              ).copy(
                error = key.toString,
                tickerPrefix = keyPrefix
              )
          }
        }
    )
  }

  /**
   * Creates a map of standard environment variables for interpolation in Mill config files.
   * This includes PWD, PWD_URI, WORKSPACE, MILL_VERSION, and MILL_BIN_PLATFORM.
   */
  def envForInterpolation(workDir: os.Path): Map[String, String] = {
    val workspaceDir = workDir.toString
    sys.env ++ Map(
      "PWD" -> workspaceDir,
      "PWD_URI" -> workDir.toNIO.toUri.toString,
      "WORKSPACE" -> workspaceDir,
      "MILL_VERSION" -> mill.constants.BuildInfo.millVersion,
      "MILL_BIN_PLATFORM" -> mill.constants.BuildInfo.millBinPlatform
    )
  }
}
