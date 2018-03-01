import $ivy.`org.scalariform::scalariform:0.2.5`

import mill._, scalalib._
import ammonite.ops._
import scalariform.formatter._
import scalariform.formatter.preferences._
import scalariform.parser.ScalaParserException

trait Scalariform extends ScalaModule {
  val playJsonPreferences = FormattingPreferences()
    .setPreference(SpacesAroundMultiImports, true)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(DoubleIndentConstructorArguments, false)

  def compile = {
    reformat()
    super.compile()
  }

  def reformat() = T.command {
    val files = filesToFormat(sources())
    T.ctx.log.info(s"Formatting ${files.size} Scala sources")
    files.foreach { path =>
      try {
        val formatted = ScalaFormatter.format(
          read(path),
          playJsonPreferences,
          scalaVersion = scalaVersion()
        )
        write.over(path, formatted)
      } catch {
        case ex: ScalaParserException =>
          T.ctx.log.error(s"Failed to format file: ${path}. Error: ${ex.getMessage}")
      }
    }
  }

  def checkCodeFormat() = T.command {
    filesToFormat(sources()).foreach { path =>
      try {
        val input = read(path)
        val formatted = ScalaFormatter.format(
          input,
          playJsonPreferences,
          scalaVersion = scalaVersion()
        )
        if (input != formatted) sys.error(
          s"""
             |ERROR: Scalariform check failed at file: ${path}
             |To fix, format your sources using `mill __.reformat` before submitting a pull request.
             |Additionally, please squash your commits (eg, use git commit --amend) if you're going to update this pull request.
          """.stripMargin
        )
      } catch {
        case ex: ScalaParserException =>
          T.ctx.log.error(s"Failed to format file: ${path}. Error: ${ex.getMessage}")
      }
    }
  }

  private def filesToFormat(sources: Seq[PathRef]) = {
    for {
      pathRef <- sources if exists(pathRef.path)
      file <- ls.rec(pathRef.path) if file.isFile && file.ext == "scala"
    } yield file
  }

}
