package mill.script
import mill.*
import mill.javalib.publish.*

import mill.api.Segments
import mill.api.ExternalModule
import mill.javalib.JavaModule
import mill.javalib.NativeImageModule

trait ScriptModule extends ExternalModule, JavaModule, NativeImageModule {
  def scriptConf: ScriptModule.Config
  override def moduleDeps = scriptConf.moduleDeps
  override def moduleDir = if (os.isDir(scriptConf.millScriptFile)) scriptConf.millScriptFile
  else scriptConf.millScriptFile / os.up
  override def sources = if (os.isDir(scriptConf.millScriptFile)) super.sources else Task.Sources()
  def scriptSource = Task.Source(scriptConf.millScriptFile)
  override def allSources = {
    if (os.isDir(scriptConf.millScriptFile)) super.allSources
    else Task {
      sources() ++ Seq(scriptSource())
    }
  }
  private[mill] def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(
      scriptConf.millScriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*
    )
  }
  override def buildOverrides = ScriptModule.parseHeaderData(scriptConf.millScriptFile)
}

object ScriptModule {
  type Config = Config0[JavaModule]
  case class Config0[+M <: JavaModule](millScriptFile: os.Path, moduleDeps: Seq[M])
  private[mill] def parseHeaderData(millScriptFile: os.Path) = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      if (os.exists(millScriptFile / "mill.yaml")) os.read(millScriptFile / "mill.yaml")
      else mill.constants.Util.readBuildHeader(millScriptFile.toNIO, millScriptFile.last, true)
    }
    upickle.read[Map[String, ujson.Value]](mill.internal.Util.parseHeaderData(headerData))
  }
  trait Publish extends mill.javalib.PublishModule {
    def pomSettings = PomSettings(
      description = "<description>",
      organization = "",
      url = "",
      licenses = Seq(),
      versionControl = VersionControl(),
      developers = Seq()
    )

    def publishVersion = Task {
      "0.0.1"
    }
  }
}
