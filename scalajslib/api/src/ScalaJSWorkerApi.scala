package mill.scalajslib.api

import java.io.File
import mill.api.Result
import upickle.default.{ReadWriter => RW, macroRW}

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
  ): Result[LinkedModules]

  def run(config: JsEnvConfig, linkedModules: LinkedModules): Unit

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      linkedModules: LinkedModules,
      moduleKind: ModuleKind
  ): (() => Unit, sbt.testing.Framework)

}

case class LinkedModules(modules: Map[String, File], moduleKind: ModuleKind)

sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

sealed trait ModuleKind
object ModuleKind {
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
  object ESModule extends ModuleKind
}

sealed trait ESVersion
object ESVersion {
  implicit val rw: RW[ESVersion] = macroRW[ESVersion]
  object ES2015 extends ESVersion
  object ES2016 extends ESVersion
  object ES2017 extends ESVersion
  object ES2018 extends ESVersion
  object ES2019 extends ESVersion
  object ES2020 extends ESVersion
  object ES2021 extends ESVersion
  object ES5_1 extends ESVersion
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

sealed trait JsEnvConfig
object JsEnvConfig {
  implicit def rwNodeJs: RW[NodeJs] = macroRW
  implicit def rwJsDom: RW[JsDom] = macroRW
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

  final case class Phantom(
      executable: String,
      args: List[String],
      env: Map[String, String],
      autoExit: Boolean
  ) extends JsEnvConfig
}
