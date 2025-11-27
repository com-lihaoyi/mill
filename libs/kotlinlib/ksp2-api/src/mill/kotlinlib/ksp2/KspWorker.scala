package mill.kotlinlib.ksp2

trait KspWorker extends AutoCloseable {

  def runKsp(
      symbolProcessorClassloader: ClassLoader,
      kspWorkerArgs: KspWorkerArgs,
      symbolProcessingArgs: Seq[String]
  ): Unit

  override def close(): Unit = {
    // no-op
  }

}
