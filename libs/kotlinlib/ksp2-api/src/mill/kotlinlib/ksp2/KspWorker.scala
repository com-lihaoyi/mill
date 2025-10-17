package mill.kotlinlib.ksp2

trait KspWorker {

  def runKsp(
      symbolProcessorClassloader: ClassLoader,
      kspWorkerArgs: KspWorkerArgs,
      symbolProcessingArgs: Seq[String]
  ): Unit

}
