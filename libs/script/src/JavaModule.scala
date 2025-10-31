package mill.script
import mill.*
import mill.api.{Discover, ExternalModule}
import mill.javalib.TestModule

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

    /** Scripts default to having no source folders */
    override def sources = Task.Sources()

    /** Scripts default to having no resource folders */
    override def resources = Task.Sources()

    override def sourcesFolders = Nil

    /** Scripts default to having no compile-time resource folders */
    override def compileResources = Task.Sources()

    /** The script file itself */
    def scriptSource = Task.Source(scriptConfig.simpleModulePath)

    override def allSources = Seq(scriptSource())
  }
}
