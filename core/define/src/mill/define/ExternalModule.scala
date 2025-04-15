package mill.define

/**
 * A module defined outside of the `build.mill` file, and is instead
 * provided builtin by some Mill library or plugin
 */
abstract class ExternalModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File
) extends BaseModule(WorkspaceRoot.workspaceRoot, external0 = true)(
      millModuleEnclosing0,
      millModuleLine0,
      millFile0
    ) {

  assert(
    !" #".exists(millModuleEnclosing0.value.contains(_)),
    "External modules must be at a top-level static path, not " + millModuleEnclosing0.value
  )
  override def moduleSegments: Segments = {
    Segments(millModuleEnclosing0.value.split('.').map(Segment.Label(_)).toIndexedSeq)
  }
}

object ExternalModule {

  /**
   * Allows you to define a new top-level [[ExternalModule]] that is simply an alias
   * to an existing one. Useful for renaming an [[ExternalModule]] while preserving
   * backwards compatibility to the existing implementation and name
   */
  class Alias(val value: ExternalModule)
}
