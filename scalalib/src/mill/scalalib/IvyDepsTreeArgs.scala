package mill.scalalib

import mainargs.Flag
import mainargs.arg

/**
 * Arguments for the ivDepsTree command.
 *
 * @param inverse Invert the tree representation, so that the root is on the bottom.
 * @param withCompile Include the compile-time only dependencies (`compileLibraryDeps`, provided scope) into the tree.
 * @param withRuntime Include the runtime dependencies (`runLibraryDeps`, runtime scope) into the tree.
 * @param whatDependsOn possible list of modules (org:artifact) to target in the tree in order to see
 *                      where a dependency stems from.
 */
class LibraryDepsTreeArgs private (
    @arg(
      doc =
        "Invert the tree representation, so that the root is on the bottom val inverse (will be forced when used with whatDependsOn)"
    )
    val inverse: Flag,
    @arg(doc =
      "Include the compile-time only dependencies (`compileLibraryDeps`, provided scope) into the tree."
    )
    val withCompile: Flag,
    @arg(doc = "Include the runtime dependencies (`runLibraryDeps`, runtime scope) into the tree.")
    val withRuntime: Flag,
    @arg(
      doc =
        "Possible list of modules (org:artifact) to target in the tree in order to see where a dependency stems from."
    )
    val whatDependsOn: List[String]
)

object LibraryDepsTreeArgs {
  def apply(
      inverse: Flag = Flag(),
      withCompile: Flag = Flag(),
      withRuntime: Flag = Flag(),
      whatDependsOn: List[String] = List.empty
  ) = new LibraryDepsTreeArgs(inverse, withCompile, withRuntime, whatDependsOn)

  implicit val argsReader: mainargs.ParserForClass[LibraryDepsTreeArgs] =
    mainargs.ParserForClass[LibraryDepsTreeArgs]
}
