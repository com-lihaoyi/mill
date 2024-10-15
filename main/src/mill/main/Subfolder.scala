package mill.main;
import mill._
import mill.define.{Caller, Ctx, Segments}
object SubfolderModule {

  class Info(val value: os.Path, val millSourcePath0: Seq[String]) {
    implicit val subFolderInfo: Info = this
  }
}

abstract class SubfolderModule()(implicit
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    subFolderInfo: SubfolderModule.Info
) extends mill.define.Module.BaseClass()(
      Ctx.make(
        millModuleEnclosing0 = subFolderInfo.millSourcePath0.mkString("."),
        millModuleLine0 = millModuleLine0,
        millModuleBasePath0 = Ctx.BasePath(subFolderInfo.value / os.up),
        segments0 = Segments.labels(subFolderInfo.millSourcePath0.init: _*),
        external0 = Ctx.External(false),
        foreign0 = Ctx.Foreign(None),
        fileName = millFile0,
        enclosing = Caller(null)
      )
    ) with Module {}
