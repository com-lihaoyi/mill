package mill.scalalib.idea

import mill.scalalib.ScalaModule
import mill.Task
import mill.api.daemon.internal.internal
import mill.api.ModuleCtx
trait GenIdeaModule extends mill.javalib.idea.GenIdeaModule {
  def javaModuleRef: mill.api.ModuleRef[ScalaModule]
  override def scalaCompilerClasspath = Task.Anon(javaModuleRef().scalaCompilerClasspath())

  override def scalacPluginsMvnDeps = javaModuleRef().scalacPluginMvnDeps

  override def allScalacOptions = Task.Anon {
    // Strip `-sourceroot <path>` pairs: IntelliJ manages source roots itself, and the
    // value would otherwise be embedded into the generated `scala_compiler.xml` and
    // depend on the workspace location.
    val (out, _) = javaModuleRef().allScalacOptions().foldLeft((Vector.empty[String], false)) {
      case ((acc, _), "-sourceroot") => (acc, true) // skip this flag
      case ((acc, true), _) => (acc, false) // and its value
      case ((acc, false), x) => (acc :+ x, false)
    }
    out
  }

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
