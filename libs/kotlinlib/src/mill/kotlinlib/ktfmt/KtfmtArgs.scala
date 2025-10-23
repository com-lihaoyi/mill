package mill.kotlinlib.ktfmt

/**
 * Arguments for [[KtfmtModule.ktfmt]].
 *
 * @param style               formatting style to apply, can be either "kotlin", "meta" or "google". Default is "kotlin".
 * @param format              if auto-formatting should be done. Default is "true"
 * @param removeUnusedImports flag to remove unused imports if auto-formatting is applied. Default is "true".
 */
@mainargs.main(doc = "arguments for KtfmtModule.ktfmt")
case class KtfmtArgs(
    style: String = "kotlin",
    format: Boolean = true,
    removeUnusedImports: Boolean = true
)

object KtfmtArgs {

  implicit val PFC: mainargs.ParserForClass[KtfmtArgs] = mainargs.Parser[KtfmtArgs]
}
