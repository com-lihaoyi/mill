package mill.script
import mill.*
import mill.javalib.publish.*

import mill.api.Segments
import mill.api.ExternalModule
import mill.javalib.JavaModule
import mill.javalib.NativeImageModule

trait ScriptModule extends ExternalModule, JavaModule, NativeImageModule {
  def millScriptFile: os.Path
  override def sources = Nil
  def selfSource = Task.Source(millScriptFile)
  override def allSources = sources() ++ Seq(selfSource())
  def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(millScriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*)
  }
  override def buildOverrides = ScriptModule.parseHeaderData(millScriptFile)
}

object ScriptModule {
  def parseHeaderData(millScriptFile: os.Path) = {
    val headerData =
      mill.constants.Util.readBuildHeader(millScriptFile.toNIO, millScriptFile.last, true)
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
