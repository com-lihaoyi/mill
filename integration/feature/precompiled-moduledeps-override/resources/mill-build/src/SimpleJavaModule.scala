package millbuild

import mill.*
import mill.api.{BuildCtx, Discover, Module, PrecompiledModule}

class SimpleJavaModule(val scriptConfig: PrecompiledModule.Config)
    extends mill.javalib.JavaModule
    with PrecompiledModule {
  override lazy val millDiscover = Discover[this.type]

  // Dispatch by the module's own segment name, walking the discovered sibling
  // tree via `BuildCtx.rootModule`. This is the pattern the integration test
  // exercises: `foo` ends up depending on `bar`, every other module depends on
  // nothing. The override would see a partial (or empty) sibling set if the
  // discovery walk hadn't completed before `moduleDeps` is queried.
  override def moduleDeps: Seq[mill.javalib.JavaModule] = {
    def sibling(name: String): Seq[mill.javalib.JavaModule] =
      BuildCtx.rootModule
        .asInstanceOf[Module]
        .moduleInternal
        .modules
        .collect { case m: SimpleJavaModule if m.moduleSegments.last.value == name => m }
        .toSeq

    moduleSegments.last.value match {
      case "foo" => sibling("bar")
      case _ => Seq.empty
    }
  }

  def listModuleDeps = Task {
    moduleDeps.map(_.moduleSegments.render)
  }
}
