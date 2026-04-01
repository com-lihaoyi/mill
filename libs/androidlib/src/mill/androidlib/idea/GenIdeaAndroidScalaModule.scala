package mill.androidlib.idea

import mill.androidlib.AndroidScalaModule
import mill.api.daemon.experimental
import mill.api.daemon.internal.internal
import mill.api.ModuleCtx

@experimental
trait GenIdeaAndroidScalaModule extends mill.androidlib.idea.GenIdeaAndroidModule,
      mill.scalalib.idea.GenIdeaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidScalaModule]
}

@internal
object GenIdeaAndroidScalaModule {
  trait Wrap(javaModule0: AndroidScalaModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = javaModule0.moduleCtx

    @internal
    object internalGenIdea extends GenIdeaAndroidScalaModule {
      def javaModuleRef = mill.api.ModuleRef(javaModule0)
    }
  }
}
