package mill.scalalib.idea

import java.nio.file.Path
import mill.scalalib.ScalaModule
import mill.Task
import mill.api.Segments
import mill.api.daemon.internal.idea.{GenIdeaInternalApi, ResolvedModule}
import mill.api.daemon.internal.internal
import mill.api.{Discover, ExternalModule, ModuleCtx, PathRef}
import mill.javalib.{BoundDep, Dep, JavaModule}
import mill.api.JsonFormatters.given
trait GenIdeaModule extends mill.javalib.idea.GenIdeaModule {
  def javaModuleRef: mill.api.ModuleRef[ScalaModule]
  override def scalaCompilerClasspath = Task.Anon(javaModuleRef().scalaCompilerClasspath())

  override def scalacPluginsMvnDeps = javaModuleRef().scalacPluginMvnDeps

  override def allScalacOptions = javaModuleRef().allScalacOptions

  override def scalaVersion = Task.Anon { Some(javaModuleRef().scalaVersion()) }

}

@internal
object GenIdeaModule {
  trait Wrap(javaModule0: ScalaModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = javaModule0.moduleCtx

    @internal
    object internalGenIdea extends GenIdeaModule {
      def javaModuleRef = mill.api.ModuleRef(javaModule0)
    }
  }
}
