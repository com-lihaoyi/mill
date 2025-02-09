package mill.main;
import mill.*
import mill.define.{Ctx, Discover, EnclosingClass, Segments}

object SubfolderModule {
  class Info(val millSourcePath0: os.Path, val segments: Seq[String]) {
    implicit val subFolderInfo: Info = this
  }
}

abstract class SubfolderModule()(implicit
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    subFolderInfo: SubfolderModule.Info
) extends mill.define.Module.BaseClass()(
      Ctx.make(
        millModuleEnclosing0 = subFolderInfo.segments.mkString("."),
        millModuleLine0 = millModuleLine0,
        millSourcePath = subFolderInfo.millSourcePath0 / os.up,
        segments0 = Segments.labels(subFolderInfo.segments.init*),
        external0 = false,
        fileName = millFile0,
        enclosingModule = null,
        enclosingClass = EnclosingClass(null),
        discover = null
      )
    ) with Module {
  def millDiscover: Discover = sys.error("RootModule#millDiscover must be overridden")
  override def millOuterCtx = super.millOuterCtx.withDiscover(millDiscover)
}
