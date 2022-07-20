package mill

import mainargs.ParserForClass
import ammonite.repl.tools.Util.PathRead

object MillConfigParser {

  val customName = s"Mill Build Tool, version ${BuildInfo.millVersion}"
  val customDoc = "usage: mill [options] [[target [target-options]] [+ [target ...]]]"

  private[this] lazy val parser: ParserForClass[MillConfig] = mainargs.ParserForClass[MillConfig]

  lazy val usageText = parser.helpText(customName = customName, customDoc = customDoc)

  def parse(args: Array[String]): Either[String, MillConfig] = {
    parser.constructEither(
      args,
      allowRepeats = true,
      autoPrintHelpAndExit = None,
      customName = customName,
      customDoc = customDoc
    )
      .map { config =>
        config.copy(
          ammoniteCore = config.ammoniteCore.copy(home = config.home)
        )
      }
  }

}
