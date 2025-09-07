package mill.meta
import mill.*
import mill.javalib.publish._
import mill.api.Discover
import mill.util.MainRootModule

trait ScriptModule extends MainRootModule with mill.javalib.JavaModule
  with mill.javalib.NativeImageModule
  with mill.javalib.PublishModule {
  def millFile: os.Path
  override def allSourceFiles = Task.Sources(millFile)
  def pomSettings = PomSettings(
    description = "<description>",
    organization = "",
    url = "",
    licenses = Seq(),
    versionControl = VersionControl(),
    developers = Seq()
  )
  def publishVersion = Task { "0.0.1" }
}
object ScriptModule {
  trait Java(val millFile: os.Path) extends ScriptModule {
    override lazy val millDiscover = Discover[this.type]

  }
  trait Scala(val millFile: os.Path) extends ScriptModule with mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
    override def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
  trait Kotlin(val millFile: os.Path) extends ScriptModule with mill.kotlinlib.KotlinModule {
    def kotlinVersion = "1.9.24"
    override def allSourceFiles = Task.Sources(millFile)
    override lazy val millDiscover = Discover[this.type]
  }
}
