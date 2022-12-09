package mill.scalajslib.api

import java.io.File
import mill.api.Result
import upickle.default.{ReadWriter => RW, macroRW}

sealed trait ModuleKind
object ModuleKind {
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
  object ESModule extends ModuleKind

  implicit def rwNoModule: RW[NoModule.type] = macroRW
  implicit def rwCommonJSModule: RW[CommonJSModule.type] = macroRW
  implicit def rwESModule: RW[ESModule.type] = macroRW
  implicit def rw: RW[ModuleKind] = macroRW
}

sealed trait ESVersion
object ESVersion {
  object ES2015 extends ESVersion
  implicit val rw2015: RW[ES2015.type] = macroRW
  object ES2016 extends ESVersion
  implicit val rw2016: RW[ES2016.type] = macroRW
  object ES2017 extends ESVersion
  implicit val rw2017: RW[ES2017.type] = macroRW
  object ES2018 extends ESVersion
  implicit val rw2018: RW[ES2018.type] = macroRW
  object ES2019 extends ESVersion
  implicit val rw2019: RW[ES2019.type] = macroRW
  object ES2020 extends ESVersion
  implicit val rw2020: RW[ES2020.type] = macroRW
  object ES2021 extends ESVersion
  implicit val rw2021: RW[ES2021.type] = macroRW
  object ES5_1 extends ESVersion
  implicit val rw5_1: RW[ES5_1.type] = macroRW

  implicit val rw: RW[ESVersion] = macroRW[ESVersion]
}

case class ESFeatures private (
    allowBigIntsForLongs: Boolean,
    avoidClasses: Boolean,
    avoidLetsAndConsts: Boolean,
    esVersion: ESVersion
) {
  def withAllowBigIntsForLongs(allowBigIntsForLongs: Boolean): ESFeatures =
    copy(allowBigIntsForLongs = allowBigIntsForLongs)
  def withAvoidClasses(avoidClasses: Boolean): ESFeatures = copy(avoidClasses = avoidClasses)
  def withAvoidLetsAndConsts(avoidLetsAndConsts: Boolean): ESFeatures =
    copy(avoidLetsAndConsts = avoidLetsAndConsts)
  def withESVersion(esVersion: ESVersion): ESFeatures = copy(esVersion = esVersion)
}
object ESFeatures {
  val Defaults: ESFeatures = ESFeatures(
    allowBigIntsForLongs = false,
    avoidClasses = true,
    avoidLetsAndConsts = true,
    esVersion = ESVersion.ES2015
  )
  implicit val rw: RW[ESFeatures] = macroRW[ESFeatures]
}

sealed trait ModuleSplitStyle
object ModuleSplitStyle {
  case object FewestModules extends ModuleSplitStyle
  implicit val rwFewestModules: RW[FewestModules.type] = macroRW
  case object SmallestModules extends ModuleSplitStyle
  implicit val rwSmallestModules: RW[SmallestModules.type] = macroRW
  final case class SmallModulesFor(packages: List[String]) extends ModuleSplitStyle
  implicit val rwSmallModulesFor: RW[SmallModulesFor] = macroRW

  implicit val rw: RW[ModuleSplitStyle] = macroRW
}

sealed trait JsEnvConfig
object JsEnvConfig {
  implicit def rwNodeJs: RW[NodeJs] = macroRW
  implicit def rwJsDom: RW[JsDom] = macroRW
  implicit def rwExoegoJsDomNodeJs: RW[ExoegoJsDomNodeJs] = macroRW
  implicit def rwPhantom: RW[Phantom] = macroRW
  implicit def rw: RW[JsEnvConfig] = macroRW

  final case class NodeJs(
      executable: String = "node",
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty,
      sourceMap: Boolean = true
  ) extends JsEnvConfig

  final case class JsDom(
      executable: String = "node",
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty
  ) extends JsEnvConfig

  final case class ExoegoJsDomNodeJs(
      executable: String = "node",
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty
  ) extends JsEnvConfig

  final case class Phantom(
      executable: String,
      args: List[String],
      env: Map[String, String],
      autoExit: Boolean
  ) extends JsEnvConfig
}
