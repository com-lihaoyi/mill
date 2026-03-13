package mill.androidlib.idea

import mill.androidlib.AndroidModule
import mill.api.daemon.experimental
import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, Task, PathRef}

@experimental
trait GenIdeaModule extends mill.javalib.idea.GenIdeaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidModule]

  override private[mill] def extDependencies = Task {
    super.extDependencies().filter(_.path.ext != "aar")
      ++ javaModuleRef().androidUnpackedAarMvnDeps().flatMap(_.classesJar)
  }

  /**
   * Generated R.java sources are not passed to [[AndroidModule.generatedSources]],
   * but they should still be passed down to the IDE for correct source navigation.
   */
  override private[mill] def moduleGeneratedSources = Task {
    val superSources = super.moduleGeneratedSources()
    val rSourcesDirs =
      Seq(javaModuleRef().androidLinkedResources().path / "generatedSources").map(PathRef(_))

    superSources ++ rSourcesDirs
  }

}

@internal
object GenIdeaModule {
  trait Wrap(javaModule0: AndroidModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = javaModule0.moduleCtx

    @internal
    object internalGenIdea extends GenIdeaModule {
      def javaModuleRef = mill.api.ModuleRef(javaModule0)
    }
  }
}
