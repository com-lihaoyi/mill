package mill.define

import mill.api.WorkspaceRoot

abstract class ExternalModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line
) extends BaseModule(WorkspaceRoot.workspaceRoot, external0 = true)(
      implicitly,
      implicitly,
      implicitly
    ) {

  assert(
    !" #".exists(millModuleEnclosing0.value.contains(_)),
    "External modules must be at a top-level static path, not " + millModuleEnclosing0.value
  )
  override def moduleSegments: Segments = {
    Segments(millModuleEnclosing0.value.split('.').map(Segment.Label(_)).toIndexedSeq)
  }

  override def moduleCtx = super.moduleCtx.withDiscover(millDiscover)
}
