package millbuild
import mill.*
import mill.javalib.*
import mill.javalib.errorprone.ErrorProneModule
trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(Deps.commonsText)

  def javacOptions = Seq("-source", "11", "-target", "11")

  trait Tests extends MavenTests, TestModule.Junit5 {

    def mvnDeps = Seq(Deps.junitJupiter)

    def runMvnDeps = Seq(mvn"org.junit.platform:junit-platform-launcher")

    def bomMvnDeps = Seq(Deps.junitBom)

    def javacOptions = Seq("-source", "11", "-target", "11")

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
