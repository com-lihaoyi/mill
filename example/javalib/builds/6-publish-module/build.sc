//// SNIPPET:BUILD
import mill._, javalib._, publish._

object foo extends JavaModule with PublishModule {
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
  )
}

// This is an example `JavaModule` with added publishing capabilities via
// `PublishModule`. This requires that you define an additional
// `publishVersion` and `pomSettings` with the relevant metadata, and provides
// the `.publishLocal` and `publishSigned` tasks for publishing locally to the
// machine or to the central maven repository

/** Usage

> mill foo.publishLocal
Publishing Artifact(com.lihaoyi,foo,0.0.1) to ivy repo...

*/
