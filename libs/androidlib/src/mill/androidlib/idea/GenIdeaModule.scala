package mill.androidlib.idea

import mill.androidlib.AndroidModule
import mill.api.daemon.experimental
import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, Task}

@experimental
trait GenIdeaModule extends mill.javalib.idea.GenIdeaModule {

  override private[mill] def extDependencies = Task {
    super.extDependencies().filter(_.path.ext != "aar")
      ++ javaModuleRef().androidUnpackedAarMvnDeps().flatMap(_.classesJar)
  }

  def javaModuleRef: mill.api.ModuleRef[AndroidModule]

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
