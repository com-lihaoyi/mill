package mill.api.internal

import mill.*
import mill.api.{Discover, Module, ModuleCtx, Segments}

object SubfolderModule {
  @deprecated(
    "No longer needed; SubfolderModule derives info from RootModule.Info and package name"
  )
  class Info(val millSourcePath0: os.Path, val segments: Seq[String]) {
    implicit val subFolderInfo: Info = this
  }

  private[mill] def segmentsFromPackageName(pkg: String): Seq[String] = {
    val prefix = mill.constants.CodeGenConstants.globalPackagePrefix
    val stripped = pkg.stripPrefix(prefix)
    if (stripped.isEmpty || stripped == ".") Seq.empty
    else stripped.stripPrefix(".").split('.').toSeq
  }
}

abstract class SubfolderModule(millDiscover: Discover)(using
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    rootModuleInfo: RootModule.Info
) extends mill.api.Module.BaseClass(using
      null.asInstanceOf[ModuleCtx]
    ) with Module {

  override lazy val moduleCtx: ModuleCtx = {
    val segments = SubfolderModule.segmentsFromPackageName(this.getClass.getPackageName)
    ModuleCtx.makeRoot(
      millModuleEnclosing0 = segments.mkString("."),
      millModuleLine0 = millModuleLine0,
      millSourcePath = rootModuleInfo.projectRoot / os.SubPath(segments.mkString("/")),
      segments0 = Segments.labels(segments*),
      external0 = false,
      fileName = millFile0
    ).withDiscover(millDiscover)
  }
}
