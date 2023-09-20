import mill._, scalalib._, publish._

object Deps {
  def acyclic = ivy"com.lihaoyi:::acyclic:0.3.6"
  def scalaCompiler(scalaVersion: String) = ivy"org.scala-lang:scala-compiler:$scalaVersion"
  val utest = ivy"com.lihaoyi::utest:0.8.1"
}

val crosses =
  Seq("2.11.12") ++
  Range.inclusive(8, 17).map("2.12." + _) ++
  Range.inclusive(0, 10).map("2.13." + _)

object acyclic extends Cross[AcyclicModule](crosses)
trait AcyclicModule extends CrossScalaModule with PublishModule {
  def crossFullScalaVersion = true
  def artifactName = "acyclic"
  def publishVersion = "1.3.3.7"

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/com-lihaoyi/acyclic",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github(owner = "com-lihaoyi", repo = "acyclic"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi")
    )
  )

  def compileIvyDeps = Agg(Deps.scalaCompiler(crossScalaVersion))

  object test extends ScalaTests with TestModule.Utest {
    def sources = T.sources(millSourcePath / "src", millSourcePath / "resources")
    def ivyDeps = Agg(Deps.utest, Deps.scalaCompiler(crossScalaVersion))
  }
}

// Acyclic is an example of a very small project that is a Scala compiler
// plugin. It is cross-built against all point versions of Scala from 2.11.12
// to 2.13.10, and has a dependency on the `org.scala-lang:scala-compiler`
//
// Project home: https://github.com/com-lihaoyi/acyclic

/** Usage

> ./mill resolve acyclic[_].compile
acyclic[2.11.12].compile
acyclic[2.12.10].compile
acyclic[2.12.11].compile
acyclic[2.12.12].compile
acyclic[2.12.13].compile
acyclic[2.12.14].compile
acyclic[2.12.15].compile
acyclic[2.12.16].compile
acyclic[2.12.8].compile
acyclic[2.12.9].compile
acyclic[2.13.0].compile
acyclic[2.13.1].compile
acyclic[2.13.2].compile
acyclic[2.13.3].compile
acyclic[2.13.4].compile
acyclic[2.13.5].compile
acyclic[2.13.6].compile
acyclic[2.13.7].compile
acyclic[2.13.8].compile
acyclic[2.13.9].compile

> ./mill acyclic[2.12.17].compile
compiling 6 Scala sources...
...

> ./mill acyclic[2.13.10].test.testLocal # acyclic tests need testLocal due to classloader assumptions
-------------------------------- Running Tests --------------------------------
...

*/