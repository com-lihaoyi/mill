package mill.contrib.checkstyle

import mainargs.{Leftover, ParserForClass, main}

/**
 * Arguments for [[CheckstyleModule.checkstyle]].
 *
 * @param check  if an exception should be raised on violations
 * @param stdout if Checkstyle output should be written to console
 * @param files  files(s) or folder(s) to process
 */
@main(doc = "arguments for CheckstyleModule.checkstyle")
case class CheckstyleArgs(check: Boolean = false, stdout: Boolean = false, files: Leftover[String])
object CheckstyleArgs {

  implicit val PFC: ParserForClass[CheckstyleArgs] = ParserForClass[CheckstyleArgs]
}
