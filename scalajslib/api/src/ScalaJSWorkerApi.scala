package mill.scalajslib.api
import java.io.File
import mill.api.Result

trait ScalaJSWorkerApi {
  def link(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: String,
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      useECMAScript2015: Boolean
  ): Result[File]

  def linkJs(
      sources: Array[File],
      libraries: Array[File],
      destDirectory: File,
      main: Option[String],
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      moduleSplitStyle: ModuleSplitStyle,
      moduleInitializers: Seq[ModuleInitializer],
      outputPatterns: OutputPatterns,
      useECMAScript2015: Boolean
  ): Result[File]

  def run(config: JsEnvConfig, linkedFile: File): Unit

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      linkedFile: File,
      moduleKind: ModuleKind
  ): (() => Unit, sbt.testing.Framework)

}

sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

sealed trait ModuleKind
object ModuleKind {
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
  object ESModule extends ModuleKind
}

sealed trait ModuleSplitStyle
object ModuleSplitStyle {
  object FewestModules extends ModuleSplitStyle
  object SmallestModules extends ModuleSplitStyle
}

object ModuleInitializer {
  import upickle.default.{ReadWriter => RW, macroRW}
  implicit def rw: RW[ModuleInitializer] = macroRW
}

case class ModuleInitializer(className: String, mainMethod: String, moduleId: String)

sealed trait OutputPatterns

object OutputPatterns {
  import upickle.default.{ReadWriter => RW, macroRW}
  implicit def rwFromJsFile: RW[OutputPatternsFromJsFile] = macroRW
  implicit def rw: RW[OutputPatterns] = macroRW

  def fromJSFile(jsFile: String): OutputPatterns = OutputPatternsFromJsFile(jsFile)

  case object OutputPatternsDefaults extends OutputPatterns

  case class OutputPatternsFromJsFile(jsFile: String) extends OutputPatterns

}

sealed trait JsEnvConfig
object JsEnvConfig {

  import upickle.default.{ReadWriter => RW, macroRW}
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
