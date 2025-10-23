package mill.kotlinlib.ktlint

/**
 * Arguments for [[KtlintModule.ktlint]].
 *
 * @param check    if an exception should be raised when violations are found
 * @param format   if violations should be corrected automatically
 */
@mainargs.main(doc = "arguments for KtlintModule.ktlint")
case class KtlintArgs(format: Boolean = false, check: Boolean = true)
object KtlintArgs {

  implicit val PFC: mainargs.ParserForClass[KtlintArgs] = mainargs.Parser[KtlintArgs]
}
