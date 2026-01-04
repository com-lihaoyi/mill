package millbuild
import mill.*
import mill.api.opt.*
import mill.javalib.*
import mill.javalib.errorprone.ErrorProneModule
trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(Deps.commonsText)

  def javacOptions = Opts("-source", "11", "-target", "11")

  trait Tests extends MavenTests {

    def mvnDeps = Seq(Deps.junitJupiter)

    def runMvnDeps = Seq(mvn"org.junit.platform:junit-platform-launcher")

    def bomMvnDeps = Seq(Deps.junitBom)

    def javacOptions = Opts("-source", "11", "-target", "11")

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
