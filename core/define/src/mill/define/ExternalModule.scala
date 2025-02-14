package mill.define

import mill.api.WorkspaceRoot

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
