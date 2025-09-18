package mill.javalib
import mill.api.{ExternalModule, Task}
import mill.api.daemon.Segments
import mill.javalib.{JavaModule, NativeImageModule}



trait SimpleModule extends ExternalModule, JavaModule, NativeImageModule {
  def simpleConf: SimpleModule.Config
  override def moduleDeps = simpleConf.moduleDeps
  override def moduleDir = if (os.isDir(simpleConf.simpleModulePath)) simpleConf.simpleModulePath
  else simpleConf.simpleModulePath / os.up
  override def sources = if (os.isDir(simpleConf.simpleModulePath)) super.sources else Task.Sources()
  def scriptSource = Task.Source(simpleConf.simpleModulePath)
  override def allSources = {
    if (os.isDir(simpleConf.simpleModulePath)) super.allSources
    else Task {
      sources() ++ Seq(scriptSource())
    }
  }
  private[mill] def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(
      simpleConf.simpleModulePath.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*
    )
  }
  override def buildOverrides: Map[String, ujson.Value] = SimpleModule.parseHeaderData(simpleConf.simpleModulePath)
}

object SimpleModule {
  type Config = Config0[JavaModule]
  case class Config0[+M <: JavaModule](simpleModulePath: os.Path, moduleDeps: Seq[M])
  private[mill] def parseHeaderData(millSimplePath: os.Path) = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      if (os.exists(millSimplePath / "mill.yaml")) os.read(millSimplePath / "mill.yaml")
      else mill.constants.Util.readBuildHeader(millSimplePath.toNIO, millSimplePath.last, true)
    }
    upickle.read[Map[String, ujson.Value]](mill.internal.Util.parsedHeaderData(headerData))
  }
  trait Publish extends mill.javalib.PublishModule {
    def pomSettings = Task{ ??? }

    def publishVersion = Task { ??? }
  }
}
