package mill.androidlib.databinding

trait AndroidDataBindingWorker {
  def processResources(args: ProcessResourcesArgs): Unit
  def generateBindingSources(args: GenerateBindingSourcesArgs): Unit
}
