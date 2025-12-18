package millbuild
import mill.*
import mill.javalib.*
import mill.javalib.errorprone.ErrorProneModule
trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(Deps.commonsText)

  def javacOptions = Seq("-source", "17", "-target", "17")

  trait Tests extends MavenTests {

    def mvnDeps = Seq(Deps.junitJupiter)

    def runMvnDeps = Seq(mvn"org.junit.platform:junit-platform-launcher")

    def bomMvnDeps = Seq(Deps.junitBom)

    def javacOptions = Seq("-source", "17", "-target", "17")

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
