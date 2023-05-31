import mill._, scalalib._, publish._
import de.tobiasroeser.mill.vcs.version.VcsVersion

object Deps {
  def acyclic = ivy"com.lihaoyi:::acyclic:0.3.6"
  def scalaCompiler(scalaVersion: String) = ivy"org.scala-lang:scala-compiler:$scalaVersion"
  val utest = ivy"com.lihaoyi::utest:0.8.1"
}

val crosses =
  Seq("2.11.12") ++
  Range(8, 17).map("2.12." + _) ++
  Range(0, 10).map("2.13." + _)

object acyclic extends Cross[AcyclicModule](crosses)
trait AcyclicModule extends CrossScalaModule with PublishModule {
  def crossFullScalaVersion = true
  def artifactName = "acyclic"
  def publishVersion = VcsVersion.vcsState().format()

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
  def compileIvyDeps = Agg(Deps.scalaCompiler(crossScalaVersion), Deps.acyclic)

  def scalacPluginIvyDeps = Agg(Deps.acyclic)

  object test extends ScalaModuleTests with TestModule.Utest {
    def sources = T.sources(millSourcePath / "src", millSourcePath / "resources")
    def ivyDeps = Agg(Deps.utest, Deps.scalaCompiler(crossScalaVersion))
  }
}