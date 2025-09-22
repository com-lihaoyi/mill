package mill.androidlib.databinding

import mill.api.{Discover, ExternalModule}

trait AndroidDataBindingWorkerModule extends mill.Module {

  def processResources(
      androidDataBindingWorker: AndroidDataBindingWorker,
      args: ProcessResourcesArgs
  ): Unit = {
    androidDataBindingWorker.processResources(args)
  }

  def generateBindingSources(
      androidDataBindingWorker: AndroidDataBindingWorker,
      args: GenerateBindingSourcesArgs
  ): Unit = {
    androidDataBindingWorker.generateBindingSources(args)
  }

}

object AndroidDataBindingWorkerModule extends ExternalModule, AndroidDataBindingWorkerModule {
  override protected def millDiscover: Discover = Discover[this.type]
}
