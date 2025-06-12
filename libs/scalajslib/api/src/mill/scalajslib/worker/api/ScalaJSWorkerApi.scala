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

private[scalajslib] enum ModuleKind {
  case NoModule
  case CommonJSModule
  case ESModule
}

private[scalajslib] enum ESVersion {
  case ES2015
  case ES2016
  case ES2017
  case ES2018
  case ES2019
  case ES2020
  case ES2021
  case ES5_1
}

private[scalajslib] case class ESFeatures(
    allowBigIntsForLongs: Boolean,
    avoidClasses: Boolean,
    avoidLetsAndConsts: Boolean,
    esVersion: ESVersion
)

private[scalajslib] sealed trait JsEnvConfig
private[scalajslib] object JsEnvConfig {
  final case class NodeJs(
      executable: String,
      args: List[String],
      env: Map[String, String],
      sourceMap: Boolean
  ) extends JsEnvConfig

  final case class JsDom(
      executable: String,
      args: List[String],
      env: Map[String, String]
  ) extends JsEnvConfig

  final case class ExoegoJsDomNodeJs(
      executable: String,
      args: List[String],
      env: Map[String, String]
  ) extends JsEnvConfig

  final case class Phantom(
      executable: String,
      args: List[String],
      env: Map[String, String],
      autoExit: Boolean
  ) extends JsEnvConfig

  final case class Selenium(
      capabilities: Selenium.Capabilities
  ) extends JsEnvConfig
  object Selenium {
    sealed trait Capabilities
    case class ChromeOptions(headless: Boolean) extends Capabilities
    case class FirefoxOptions(headless: Boolean) extends Capabilities
    case class SafariOptions() extends Capabilities
  }
}

private[scalajslib] final case class Report(
    val publicModules: Iterable[Report.Module],
    val dest: File
)
private[scalajslib] object Report {
  final case class Module(
      val moduleID: String,
      val jsFileName: String,
      val sourceMapName: Option[String],
      val moduleKind: ModuleKind
  )
}

private[scalajslib] sealed trait ModuleSplitStyle
private[scalajslib] object ModuleSplitStyle {
  case object FewestModules extends ModuleSplitStyle
  case object SmallestModules extends ModuleSplitStyle
  final case class SmallModulesFor(packages: List[String]) extends ModuleSplitStyle
}

private[scalajslib] final case class OutputPatterns(
    jsFile: String,
    sourceMapFile: String,
    moduleName: String,
    jsFileURI: String,
    sourceMapURI: String
)

private[scalajslib] sealed trait ESModuleImportMapping
private[scalajslib] object ESModuleImportMapping {
  case class Prefix(prefix: String, replacement: String) extends ESModuleImportMapping
}
