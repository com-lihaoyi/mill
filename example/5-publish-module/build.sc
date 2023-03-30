import mill._, scalalib._, publish._

object foo extends ScalaModule with PublishModule {
  def scalaVersion = "2.13.2"
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi")
    )
  )
}

// This is an example `ScalaModule` with added publishing capabilities via
// `PublishModule`. This requires that you define an additional
// `publishVersion` and `pomSettings` with the relevant metadata, and provides
// the `.publishLocal` and `publishSigned` tasks for publishing locally to the
// machine or to the central maven repository

/* Example Usage

> ./mill foo.publishLocal
Publishing Artifact(com.lihaoyi,foo_2.13,0.0.1)

*/