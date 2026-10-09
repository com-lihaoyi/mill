package mill.scalalib.idea

import mill.scalalib.ScalaModule
import mill.Task
import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, PathRef}
import mill.api.opt.Opts
import mill.javalib.Dep

trait GenIdeaModule extends mill.javalib.idea.GenIdeaModule {

  def javaModuleRef: mill.api.ModuleRef[ScalaModule]

  override def scalaCompilerClasspath: Task[Seq[PathRef]] = Task.Anon(javaModuleRef().scalaCompilerClasspath())

  override def scalacPluginsMvnDeps: Task[Seq[Dep]] = javaModuleRef().scalacPluginMvnDeps

  override def allScalacOptions: Task[Opts] = javaModuleRef().allScalacOptions

  override def scalaVersion: Task[Option[String]] = Task.Anon { Some(javaModuleRef().scalaVersion()) }

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
