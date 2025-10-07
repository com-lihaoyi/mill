package mill.androidlib.databinding

import mill.api.{Discover, ExternalModule}

/**
 * Used for generating binding sources with the databind-compiler.
 *
 * For more info see [[https://developer.android.com/topic/libraries/view-binding]]
 * and [[https://developer.android.com/topic/libraries/data-binding]]
 */
@mill.api.experimental
trait AndroidDataBindingWorkerModule extends mill.Module {

  /**
   * Process Android resources to generate layout info files
   */
  def processResources(
      androidDataBindingWorker: AndroidDataBindingWorker,
      args: ProcessResourcesArgs
  ): Unit = {
    androidDataBindingWorker.processResources(args)
  }

  /**
   * Generate binding sources from layout info files
   */
  def generateBindingSources(
      androidDataBindingWorker: AndroidDataBindingWorker,
      args: GenerateBindingSourcesArgs
  ): Unit = {
    androidDataBindingWorker.generateBindingSources(args)
  }

}

@mill.api.experimental
object AndroidDataBindingWorkerModule extends ExternalModule, AndroidDataBindingWorkerModule {
  override protected def millDiscover: Discover = Discover[this.type]
}
