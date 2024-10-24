package mill.kotlinlib.detekt

import mainargs.{ParserForClass, main}

/**
 * Arguments for [[DetektModule.detekt]].
 *
 * @param check   if an exception should be raised when violations are found
 */
@main(doc = "arguments for DetektModule.detekt")
case class DetektArgs(check: Boolean = true)
object DetektArgs {

  implicit val PFC: ParserForClass[DetektArgs] = ParserForClass[DetektArgs]
}
