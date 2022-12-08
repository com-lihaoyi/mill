package mill.scalajslib.worker.api

import java.io.File

private[scalajslib] trait ScalaJSWorkerApi {
  def link(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: String,
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle
  ): Either[String, Report]

  def run(config: JsEnvConfig, report: Report): Unit

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      report: Report
  ): (() => Unit, sbt.testing.Framework)

}

private[scalajslib] sealed trait ModuleKind
private[scalajslib] object ModuleKind {
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
  object ESModule extends ModuleKind
}

private[scalajslib] sealed trait ESVersion
private[scalajslib] object ESVersion {
  object ES2015 extends ESVersion
  object ES2016 extends ESVersion
  object ES2017 extends ESVersion
  object ES2018 extends ESVersion
  object ES2019 extends ESVersion
  object ES2020 extends ESVersion
  object ES2021 extends ESVersion
  object ES5_1 extends ESVersion
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
