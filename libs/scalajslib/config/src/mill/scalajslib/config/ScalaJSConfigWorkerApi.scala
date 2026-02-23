package mill.scalajslib.config

import java.io.File
import java.nio.file.Path
import org.scalajs.linker.{interface => sjs}
import mill.scalajslib.worker.api.ESModuleImportMapping
import mill.scalajslib.worker.api.JsEnvConfig
import org.scalajs.jsenv.Input

private[scalajslib] trait ScalaJSConfigWorkerApi extends AutoCloseable {
  def rawLink(
      runClasspath: Seq[Path],
      dest: File,
      moduleInitializers: Seq[sjs.ModuleInitializer],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      importMap: Seq[ESModuleImportMapping],
      config: sjs.StandardConfig
  ): Either[String, sjs.Report]

  def run0(config: JsEnvConfig, inputs: Seq[Input]): Unit

  def rawGetFramework(
      config: JsEnvConfig,
      frameworkName: String,
      inputs: Seq[Input]
  ): (() => Unit, sbt.testing.Framework)

}
