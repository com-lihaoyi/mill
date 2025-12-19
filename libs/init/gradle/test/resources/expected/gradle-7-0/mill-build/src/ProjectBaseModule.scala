package millbuild
import mill.*
import mill.javalib.*
trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(Deps.commonsText)

  def javacOptions = Seq("-source", "11", "-target", "11")

  trait Tests extends MavenTests, TestModule.Junit5 {

    def mandatoryMvnDeps =
      Seq(mvn"com.github.sbt.junit:jupiter-interface:0.11.4")

    def mvnDeps = Seq(Deps.junitJupiterApi)

    def runMvnDeps = Seq(
      mvn"org.junit.jupiter:junit-jupiter-engine",
      mvn"org.junit.platform:junit-platform-launcher"
    )

    def bomMvnDeps = Seq(Deps.junitBom)

    def javacOptions = Seq("-source", "11", "-target", "11")

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
