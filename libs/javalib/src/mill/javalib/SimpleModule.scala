package mill.javalib
import mill.api.{ExternalModule, Task}
import mill.api.daemon.Segments

trait SimpleModule extends ExternalModule {
  def simpleConf: SimpleModule.Config

  override def moduleDir =
    if (os.isDir(simpleConf.simpleModulePath)) simpleConf.simpleModulePath
    else simpleConf.simpleModulePath / os.up


  private[mill] def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(
      simpleConf.simpleModulePath.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*
    )
  }
  override def buildOverrides: Map[String, ujson.Value] =
    SimpleModule.parseHeaderData(simpleConf.simpleModulePath)
}

object SimpleModule {
  case class Config(simpleModulePath: os.Path, moduleDeps: Seq[mill.Module])
  private[mill] def parseHeaderData(millSimplePath: os.Path) = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      if (os.exists(millSimplePath / "mill.yaml")) os.read(millSimplePath / "mill.yaml")
      else mill.constants.Util.readBuildHeader(millSimplePath.toNIO, millSimplePath.last, true)
    }
    upickle.read[Map[String, ujson.Value]](mill.internal.Util.parsedHeaderData(headerData))
  }
  trait Publish extends mill.javalib.PublishModule {
    def pomSettings = Task { ??? }

    def publishVersion = Task { ??? }
  }
}
