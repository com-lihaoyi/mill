package mill.androidlib.idea

import mill.androidlib.AndroidModule
import mill.api.daemon.experimental
import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, Task, PathRef}

@experimental
trait GenIdeaAndroidModule extends mill.javalib.idea.GenIdeaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidModule]

  // Internal IDEA wrappers are outside the build's override mapping, so overridden
  // computations need task names distinct from their inherited dependencies.
  private def androidExtDependencies = Task {
    super.extDependencies().filter(_.path.ext != "aar")
      ++ javaModuleRef().androidUnpackedAarMvnDeps().flatMap(_.classesJar)
  }
  override private[mill] def extDependencies = androidExtDependencies

  /**
   * Generated R.java sources are not passed to [[AndroidModule.generatedSources]],
   * but they should still be passed down to the IDE for correct source navigation.
   */
  private def androidModuleGeneratedSources = Task {
    val superSources = super.moduleGeneratedSources()
    val rSourcesDirs =
      Seq(javaModuleRef().androidLinkedResources().generatedSourcesDir)

    superSources ++ rSourcesDirs
  }
  override private[mill] def moduleGeneratedSources = androidModuleGeneratedSources

}

@internal
object GenIdeaAndroidModule {
  trait Wrap(javaModule0: AndroidModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = javaModule0.moduleCtx

    @internal
    object internalGenIdea extends GenIdeaAndroidModule {
      def javaModuleRef = mill.api.ModuleRef(javaModule0)
    }
  }
}
