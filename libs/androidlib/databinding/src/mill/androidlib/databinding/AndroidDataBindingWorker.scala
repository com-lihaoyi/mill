package mill.androidlib.databinding

trait AndroidDataBindingWorker extends AutoCloseable {

  def processResources(args: ProcessResourcesArgs): Unit

  def generateBindingSources(args: GenerateBindingSourcesArgs): Unit

  override def close(): Unit = {
    // no-up
  }
}
