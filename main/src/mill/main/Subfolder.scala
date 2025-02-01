package mill.main;
import mill._
import mill.define.{Caller, Ctx, Segments, Discover}

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
        millModuleBasePath0 = Ctx.BasePath(subFolderInfo.millSourcePath0 / os.up),
        segments0 = Segments.labels(subFolderInfo.segments.init: _*),
        external0 = Ctx.External(false),
        foreign0 = Ctx.Foreign(None),
        fileName = millFile0,
        enclosing = Caller(null)
      )
    ) with Module {
  // SCALA 3: REINTRODUCED millDiscover because we need to splice the millDiscover from
  // child modules into the parent module - this isnt wasteful because the parent module
  // doesnt scan the children - hence why it is being spliced in in the Scala 3 version.

  // Dummy `millDiscover` defined but never actually used and overriden by codegen.
  // Provided for IDEs to think that one is available and not show errors in
  // build.mill/package.mill even though they can't see the codegen
  def millDiscover: Discover = sys.error("RootModule#millDiscover must be overriden")
}
