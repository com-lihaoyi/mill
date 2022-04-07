package mill.scalajslib.api

import java.io.File
import mill.api.Result
import upickle.default.{ReadWriter => RW, macroRW}

@deprecated("To be removed. Internal use only. Report to maintainers if you have any usage of it.", since = "mill 0.10.3")
trait ScalaJSWorkerApi {
  def link(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: String,
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  ): Result[File]

  def run(config: JsEnvConfig, linkedFile: File): Unit

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      linkedFile: File,
      moduleKind: ModuleKind
  ): (() => Unit, sbt.testing.Framework)

}
