package mill.androidlib.databinding

trait AndroidDataBinding {
  def processResources(args: ProcessResourcesArgs): Unit
  def generateBindingSources(args: GenerateBindingSourcesArgs): Unit
}
