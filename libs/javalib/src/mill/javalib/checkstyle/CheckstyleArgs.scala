package mill.javalib.checkstyle

import mainargs.{Leftover, ParserForClass, main}

/**
 * Arguments for [[CheckstyleModule.checkstyle]].
 *
 * @param check   if an exception should be raised when violations are found
 * @param stdout  if Checkstyle should output report to [[System.out]]
 * @param sources (optional) files(s) or folder(s) to process
 */
@main(doc = "arguments for CheckstyleModule.checkstyle")
case class CheckstyleArgs(
    check: Boolean = true,
    stdout: Boolean = true,
    sources: Leftover[String]
)
object CheckstyleArgs {

  implicit val PFC: ParserForClass[CheckstyleArgs] = ParserForClass[CheckstyleArgs]
}
