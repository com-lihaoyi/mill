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
    // IntelliJ manages source roots itself, so strip any `-sourceroot <path>` pairs
    // (e.g. the one `MillBuildRootModule` adds for reproducible TASTY output) before
    // they leak into the generated `scala_compiler.xml` and depend on the workspace
    // location (which would make the golden file non-portable).
    val opts = javaModuleRef().allScalacOptions()
    val buf = scala.collection.mutable.Buffer.empty[String]
    var i = 0
    while (i < opts.length) {
      if (opts(i) == "-sourceroot" && i + 1 < opts.length) i += 2
      else { buf += opts(i); i += 1 }
    }
    buf.toSeq
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
