package mill.scalajslib.api
import java.io.File
import mill.api.Result
trait ScalaJSWorkerApi {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean,
           moduleKind: ModuleKind): Result[File]

  def run(config: JsEnvConfig, linkedFile: File): Unit

  def getFramework(config: JsEnvConfig,
                   frameworkName: String,
                   linkedFile: File,
                   moduleKind: ModuleKind): (() => Unit, sbt.testing.Framework)

}


sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

sealed trait ModuleKind
object ModuleKind{
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
}


sealed trait JsEnvConfig
object JsEnvConfig{


  import upickle.default.{ReadWriter => RW, macroRW}
  implicit def rwNodeJs: RW[NodeJs] = macroRW
  implicit def rwJsDom: RW[JsDom] = macroRW
  implicit def rwPhantom: RW[Phantom] = macroRW
  implicit def rw: RW[JsEnvConfig] = macroRW

  final case class NodeJs(executable: String = "node",
                          args: List[String] = Nil,
                          env: Map[String, String] = Map.empty,
                          sourceMap: Boolean = true) extends JsEnvConfig

  final case class JsDom(executable: String = "node",
                         args: List[String] = Nil,
                         env: Map[String, String] = Map.empty) extends JsEnvConfig

  final case class Phantom(executable: String,
                           args: List[String],
                           env: Map[String, String],
                           autoExit: Boolean) extends JsEnvConfig
}
