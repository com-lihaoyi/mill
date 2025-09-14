package mill.singlefile
import mill.*
import mill.javalib.publish.*

import mill.api.Segments
import mill.api.ExternalModule
import mill.javalib.JavaModule
import mill.javalib.NativeImageModule



trait SingleFileModule extends ExternalModule, JavaModule, NativeImageModule {
  def millFile: os.Path
  override def sources = Nil
  def selfSource = Task.Source(millFile)
  override def allSources = sources() ++ Seq(selfSource())
  def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(millFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*)
  }
  override def buildOverrides = SingleFileModule.parseHeaderData(millFile)
}

object SingleFileModule {
  def parseHeaderData(millFile: os.Path) = {
    val headerData = mill.constants.Util.readBuildHeader(millFile.toNIO, millFile.last, true)
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
