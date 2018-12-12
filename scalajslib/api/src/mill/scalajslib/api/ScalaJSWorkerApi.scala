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

  def run(config: NodeJSConfig, linkedFile: File): Unit

  def getFramework(config: NodeJSConfig,
                   frameworkName: String,
                   linkedFile: File): (() => Unit, sbt.testing.Framework)

}


sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

sealed trait ModuleKind
object ModuleKind{
  object NoModule extends ModuleKind
  object CommonJSModule extends ModuleKind
}


object NodeJSConfig {
  import upickle.default.{ReadWriter => RW, macroRW}
  implicit def rw: RW[NodeJSConfig] = macroRW
}

final case class NodeJSConfig(executable: String = "node",
                              args: List[String] = Nil,
                              env: Map[String, String] = Map.empty,
                              sourceMap: Boolean = true)
