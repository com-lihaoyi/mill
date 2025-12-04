package mill.contrib.scalapblib

import mill.api.{Discover, ExternalModule, Task}

import java.io.File

trait ScalaPBWorkerApi {
  def compileScalaPB(
      roots: Seq[File],
      source: Seq[File],
      scalaPBOptions: String,
      generatedDirectory: File,
      otherArgs: Seq[String],
      generators: Seq[Generator]
  ): Unit
}

object ScalaPBWorkerApi extends ExternalModule {
  def scalaPBWorker: Task.Worker[ScalaPBWorker] = Task.Worker { ScalaPBWorker() }
  lazy val millDiscover = Discover[this.type]
}
