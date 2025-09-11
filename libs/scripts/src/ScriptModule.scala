package mill.scripts
import mill.*
import mill.javalib.publish._
import mill.api.Discover
import mill.api.ExternalModule

trait ScriptModule extends ExternalModule with mill.javalib.JavaModule
    with mill.javalib.NativeImageModule {
  def millFile: os.Path
  override def sources = Nil
  def selfSource = Task.Source(millFile)
  override def allSources = sources() ++ Seq(selfSource())
  
}
object ScriptModule {
  trait Publish extends mill.javalib.PublishModule {
    def pomSettings = PomSettings(
      description = "<description>",
      organization = "",
      url = "",
      licenses = Seq(),
      versionControl = VersionControl(),
      developers = Seq()
    )

    def publishVersion = Task {
      "0.0.1"
    }
  }
  trait Java(val millFile: os.Path) extends ScriptModule {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Scala(val millFile: os.Path) extends ScriptModule with mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
    override lazy val millDiscover = Discover[this.type]
  }

  trait Kotlin(val millFile: os.Path) extends ScriptModule with mill.kotlinlib.KotlinModule {
    def kotlinVersion = "1.9.24"
    override lazy val millDiscover = Discover[this.type]
  }
}
