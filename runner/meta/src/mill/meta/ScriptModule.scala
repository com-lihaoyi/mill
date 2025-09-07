package mill.meta

import mill.api.Discover
import mill.util.MainRootModule

object ScriptModule {
  trait Java(millFile: os.Path) extends MainRootModule with mill.javalib.JavaModule
      with mill.javalib.NativeImageModule {
    import mill.*
    override def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
  trait Scala(millFile: os.Path) extends MainRootModule with mill.scalalib.ScalaModule
      with mill.javalib.NativeImageModule {
    import mill.*
    def scalaVersion = mill.util.BuildInfo.scalaVersion
    override def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
  trait Kotlin(millFile: os.Path) extends MainRootModule with mill.kotlinlib.KotlinModule
      with mill.javalib.NativeImageModule {
    import mill.*
    def kotlinVersion = "1.9.24"
    override def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
}
