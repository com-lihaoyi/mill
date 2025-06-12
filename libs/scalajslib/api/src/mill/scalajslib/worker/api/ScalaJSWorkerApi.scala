package mill.scalajslib.worker.api

import java.io.File
import java.nio.file.Path

private[scalajslib] trait ScalaJSWorkerApi {
  def link(
      runClasspath: Seq[Path],
      dest: File,
      main: Either[String, String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns,
      minify: Boolean,
      importMap: Seq[ESModuleImportMapping],
      experimentalUseWebAssembly: Boolean
  ): Either[String, Report]

  def run(config: JsEnvConfig, report: Report): Unit

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      report: Report
  ): (() => Unit, sbt.testing.Framework)

}
