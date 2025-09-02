package mill.kotlinlib.ksp

import mill.api.{Discover, ExternalModule, PathRef}
import mill.util.Jvm

@mill.api.experimental
trait KspWorkerModule extends ExternalModule {
  def runKsp(logLevel: String, workerClasspath: Seq[PathRef], kspArgs: Seq[String]): Unit
}

@mill.api.experimental
object KspWorkerModule extends KspWorkerModule {

  def runKsp(logLevel: String, workerClasspath: Seq[PathRef], kspArgs: Seq[String]): Unit = {

    val kspClassLoader = Jvm.createClassLoader(
      workerClasspath.map(_.path),
      getClass.getClassLoader
    )

    val mainClass = "mill.kotlinlib.ksp.worker.KspWorker"

    kspClassLoader.findClass(mainClass).getMethod(
      "runKsp",
      classOf[Map[String, String]],
      classOf[Seq[String]]
    ).invoke(
      null,
      Map("logLevel" -> logLevel),
      kspArgs
    )

  }

  override protected def millDiscover: Discover = Discover[this.type]
}
