package mill.buildfile

import com.virtuslab.using_directives.Context
import com.virtuslab.using_directives.config.Settings
import com.virtuslab.using_directives.custom.utils.Source
import com.virtuslab.using_directives.custom.utils.ast.{StringLiteral, UsingDef}
import com.virtuslab.using_directives.custom.{Parser, SimpleCommentExtractor}
import com.virtuslab.using_directives.reporter.ConsoleReporter
import upickle.default.{ReadWriter, macroRW}
import mill.api.JsonFormatters._

import java.nio.charset.Charset
import scala.jdk.CollectionConverters.CollectionHasAsScala

case class ParsedMillSetup(
    projectDir: os.Path,
    directives: Seq[MillUsingDirective],
    buildScript: Option[os.Path]
) {
  lazy val includedSourceFiles: Seq[os.Path] = directives.collect {
    case MillUsingDirective.File(file, src) => projectDir / file
  }
  lazy val millVersion: Option[String] = directives.collect {
    case MillUsingDirective.MillVersion(version, src) => version
  }.headOption
  lazy val ivyDeps: Seq[String] = directives.collect {
    case MillUsingDirective.Dep(dep, src) => dep
  }
}

object ParsedMillSetup {
  implicit val upickleRW: ReadWriter[ParsedMillSetup] = macroRW
}

sealed trait MillUsingDirective {
  def sourceFile: os.Path
}

object MillUsingDirective {

  case class Dep(raw: String, sourceFile: os.Path) extends MillUsingDirective
  object Dep {
    implicit val upickleRW: ReadWriter[Dep] = macroRW
  }

  case class File(file: String, sourceFile: os.Path) extends MillUsingDirective
  object File {
    implicit val upickleRW: ReadWriter[File] = macroRW
  }

  case class MillVersion(version: String, sourceFile: os.Path) extends MillUsingDirective
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
    val (directives, buildScript, code, codeOffset) =
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
                case ("dep", v: StringLiteral) => MillUsingDirective.Dep(v.getValue, buildSc)
                case ("file", v: StringLiteral) => MillUsingDirective.File(v.getValue, buildSc)
                case ("mill.version", v: StringLiteral) =>
                  MillUsingDirective.MillVersion(v.getValue, buildSc)
              }

            }
        }.toList

        (directives, Some(buildSc), rest, parsed.getCodeOffset())

      } else (Seq.empty[MillUsingDirective], None, "", 0)

    ParsedMillSetup(
      projectDir = buildSc / os.up,
      directives = directives,
      buildScript = buildScript
    )
  }
}
