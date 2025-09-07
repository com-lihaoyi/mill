package mill.meta

import mill.api.Discover
import mill.api.internal.RootModule

object ScriptModule {
  class Java(millFile: os.Path)
            (implicit
             baseModuleInfo: RootModule.Info,
             millModuleEnclosing0: sourcecode.Enclosing,
             millModuleLine0: sourcecode.Line,
             millFile0: sourcecode.File
            )extends RootModule with mill.javalib.JavaModule {
    import mill.*
    def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
  class Scala(millFile: os.Path)
             (implicit
              baseModuleInfo: RootModule.Info,
              millModuleEnclosing0: sourcecode.Enclosing,
              millModuleLine0: sourcecode.Line,
              millFile0: sourcecode.File
             )extends RootModule with mill.scalalib.ScalaModule {
    import mill.*
    def scalaVersion = mill.util.BuildInfo.scalaVersion
    def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
  class Kotlin(millFile: os.Path)
              (implicit
               baseModuleInfo: RootModule.Info,
               millModuleEnclosing0: sourcecode.Enclosing,
               millModuleLine0: sourcecode.Line,
               millFile0: sourcecode.File
              )extends RootModule with mill.kotlinlib.KotlinModule {
    import mill.*
    def kotlinVersion = "1.9.24"
    def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
}
