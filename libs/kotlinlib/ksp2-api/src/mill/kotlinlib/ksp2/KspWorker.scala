package mill.kotlinlib.ksp2

import java.net.URLClassLoader

trait KspWorker {

  def runKsp(
      symbolProcessorClassloader: URLClassLoader,
      kspWorkerArgs: KspWorkerArgs,
      symbolProcessingArgs: Seq[String]
  ): Unit

}
