package mill.kotlinlib.detekt

/**
 * Arguments for [[DetektModule.detekt]].
 *
 * @param check   if an exception should be raised when violations are found
 */
@mainargs.main(doc = "arguments for DetektModule.detekt")
case class DetektArgs(check: Boolean = true)
object DetektArgs {

  implicit val PFC: mainargs.ParserForClass[DetektArgs] = mainargs.Parser[DetektArgs]
}
