package mill.main;
import mill.*
import mill.define.{ModuleCtx, Discover, Segments}

object SubfolderModule {
  class Info(val millSourcePath0: os.Path, val segments: Seq[String]) {
    implicit val subFolderInfo: Info = this
  }
}

abstract class SubfolderModule(millDiscover: Discover)(implicit
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    subFolderInfo: SubfolderModule.Info
) extends mill.define.Module.BaseClass()(
      ModuleCtx.makeRoot(
        millModuleEnclosing0 = subFolderInfo.segments.mkString("."),
        millModuleLine0 = millModuleLine0,
        millSourcePath = subFolderInfo.millSourcePath0,
        segments0 = Segments.labels(subFolderInfo.segments*),
        external0 = false,
        fileName = millFile0
      )
    ) with Module {
  override def moduleCtx = super.moduleCtx.withDiscover(millDiscover)
}
