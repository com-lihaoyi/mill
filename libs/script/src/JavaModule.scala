package mill.script
import mill.*
import mill.api.{Discover, ExternalModule}
import mill.javalib.{TestModule, Dep}

class JavaModule(val scriptConfig: ScriptModule.Config) extends JavaModule.Base {
  override lazy val millDiscover = Discover[this.type]
}
object JavaModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends JavaModule(scriptConfig)
      with TestModule.TestNg with mill.javalib.JavaModule.JavaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends JavaModule(scriptConfig)
      with TestModule.Junit4 with mill.javalib.JavaModule.JavaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends JavaModule(scriptConfig)
      with TestModule.Junit5 with mill.javalib.JavaModule.JavaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends ScriptModule with mill.javalib.JavaModule
      with mill.javalib.NativeImageModule {
    private[mill] def isScript: Boolean = true

    override def moduleDeps = scriptConfig.moduleDeps.map(_.asInstanceOf[mill.javalib.JavaModule])

    override def sources = Task.Sources()

    /** The script file itself */
    def scriptSource = Task.Source(scriptConfig.simpleModulePath)

    override def allSources = Seq(scriptSource())

    /**
     * Whether or not to include the default `mvnDeps` that are bundled with single-file scripts.
     */
    def includDefaultScriptMvnDeps: T[Boolean] = true

    /**
     * The default `mvnDeps` for single-file scripts. For Scala scripts that means MainArgs,
     * uPickle, Requests-Scala, OS-Lib, and PPrint. For Java and Kotlin scripts it is currently
     * empty
     */
    def defaultScriptMvnDeps = Task {
      Seq.empty[Dep]
    }

    override def mandatoryMvnDeps = Task {
      super.mandatoryMvnDeps() ++
        (if (includDefaultScriptMvnDeps()) defaultScriptMvnDeps() else Seq.empty[Dep])
    }
  }
}
