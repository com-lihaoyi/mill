package mill.kotlinlib.ksp

import mill.api.daemon.MillURLClassLoader
import mill.api.{Discover, ExternalModule}

import java.net.URLClassLoader

@mill.api.experimental
trait KspWorkerModule extends mill.Module {
  def runKsp(
      logLevel: String,
      kspClassLoader: MillURLClassLoader,
      symbolProcessorClassloader: MillURLClassLoader,
      kspArgs: Seq[String]
  ): Unit = {

    val mainClass = "mill.kotlinlib.ksp.worker.KspWorker"
    println(kspClassLoader.findClass(mainClass).getMethods.map(_.getName).mkString(","))
    kspClassLoader.findClass(mainClass).getMethod(
      "runKsp",
      classOf[Map[String, String]],
      classOf[Seq[String]],
      classOf[URLClassLoader]
    ).invoke(
      null,
      Map("logLevel" -> logLevel),
      kspArgs,
      symbolProcessorClassloader
    )
  }
}

@mill.api.experimental
object KspWorkerModule extends ExternalModule with KspWorkerModule {
  override protected def millDiscover: Discover = Discover[this.type]
}
