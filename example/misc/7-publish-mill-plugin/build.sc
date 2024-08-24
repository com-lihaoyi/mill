
import mill._, scalalib._, publish._
import mill.main.BuildInfo.millVersion

object myplugin extends ScalaModule with PublishModule {
  def scalaVersion = "2.13.8"
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Line Count Mill Plugin",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi")
    )
  )

  def ivyDeps = Agg(
    ivy"com.lihaoyi::mill-testkit:$millVersion",
    ivy"com.lihaoyi::mill-scalalib:$millVersion"
  )

  object test extends ScalaTests with TestModule.Utest{
    def forkEnv = Map(
      "MILL_EXECUTABLE_PATH" ->
        defaultResolver().resolveDeps(Seq(ivy"com.lihaoyi:mill-dist:$millVersion"))
          .head.path.toString
    )
  }
}
