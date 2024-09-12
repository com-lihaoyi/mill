package mill
package contrib.palantir

/**
 * @param styleOverride override default style with `palantir | asop`
 * @param skipSortImports do not sort imports
 * @param skipUnusedImports do not remove unused imports
 * @param skipReflowingLongStrings do not wrap long strings
 */
case class PalantirOptions(
    styleOverride: Option[String] = Some("palantir"),
    skipSortImports: Boolean = false,
    skipUnusedImports: Boolean = false,
    skipReflowingLongStrings: Boolean = false
)
object PalantirOptions {

  import upickle.default._

  implicit val RW: ReadWriter[PalantirOptions] = macroRW[PalantirOptions]
}
