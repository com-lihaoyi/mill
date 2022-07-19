package mill.buildfile

import com.virtuslab.using_directives.Context
import com.virtuslab.using_directives.config.Settings
import com.virtuslab.using_directives.custom.utils.Source
import com.virtuslab.using_directives.custom.utils.ast.{StringLiteral, UsingDef}
import com.virtuslab.using_directives.custom.{Parser, SimpleCommentExtractor}
import com.virtuslab.using_directives.reporter.ConsoleReporter
import upickle.default.{ReadWriter, macroRW}

import java.nio.charset.Charset
import scala.jdk.CollectionConverters.CollectionHasAsScala

case class ParsedMillSetup(
    projectDir: String,
    directives: Seq[MillUsingDirective],
    buildScriptPresent: Boolean
) {
  lazy val includedSourceFiles: Seq[os.Path] = directives.collect {
    case MillUsingDirective.File(file) => os.Path(projectDir) / file
  }
  lazy val millVersion: Option[String] = directives.collect {
    case MillUsingDirective.MillVersion(version) => version
  }.headOption
  lazy val ivyDeps: Seq[String] = directives.collect {
    case MillUsingDirective.Dep(dep) => dep
  }
}

object ParsedMillSetup {
//  implicit def pathUpickleRW: ReadWriter[os.Path] = upickle.default.readwriter[String].bimap[os.Path](
//    p => p.toString(),
//    s => os.Path(s)
//  )
  implicit val upickleRW: ReadWriter[ParsedMillSetup] = macroRW
}

sealed trait MillUsingDirective

object MillUsingDirective {

  case class Dep(raw: String) extends MillUsingDirective
  object Dep {
    implicit val upickleRW: ReadWriter[Dep] = macroRW
  }

  case class File(file: String) extends MillUsingDirective
  object File {
    implicit val upickleRW: ReadWriter[File] = macroRW
  }

  case class MillVersion(version: String) extends MillUsingDirective
  object MillVersion {
    implicit val upickleRW: ReadWriter[MillVersion] = macroRW
  }

  implicit val upickleRW: ReadWriter[MillUsingDirective] = ReadWriter.merge(
    Dep.upickleRW,
    File.upickleRW,
    MillVersion.upickleRW
  )
}

object ReadDirectives {

  def readUsingDirectives(buildSc: os.Path): ParsedMillSetup = {
    val (directives, exists, code, codeOffset) =
      if (os.exists(buildSc)) {
        val content = new String(os.read.bytes(buildSc), Charset.forName("UTF-8"))
        val extractor = new SimpleCommentExtractor(content.toCharArray(), true)

        val settings = new Settings(false, true)
        val context = new Context(new ConsoleReporter(), settings)

        val source = new Source(extractor.extractComments())
        val parser = new Parser(source, context)
        val parsed = parser.parse()
        val usingDefs = parsed.getUsingDefs()
//        println(s"Found using defs: ${usingDefs}")

        val rest = content.substring(parsed.getCodeOffset())

//        println(s"Rest of file: ${rest}")

        val directives = usingDefs.asScala.flatMap {
          case u: UsingDef =>
            val settings = u.getSettingDefs.getSettings
            settings.asScala.map { s =>
              (s.getKey, s.getValue) match {
                case ("dep", v: StringLiteral) => MillUsingDirective.Dep(v.getValue)
                case ("file", v: StringLiteral) => MillUsingDirective.File(v.getValue)
                case ("mill.version", v: StringLiteral) =>
                  MillUsingDirective.MillVersion(v.getValue)
              }

            }
        }.toList

        (directives, true, rest, parsed.getCodeOffset())

      } else (Seq.empty[MillUsingDirective], false, "", 0)

    ParsedMillSetup(
      projectDir = (buildSc / os.up).toString,
      directives = directives,
      buildScriptPresent = exists
//      buildScriptCode = code,
//      buildScriptCodeOffset = codeOffset
    )
  }
}
