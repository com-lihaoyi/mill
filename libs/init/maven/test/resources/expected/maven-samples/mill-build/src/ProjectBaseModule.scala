package millbuild
import mill.*
import mill.api.opt.*
import mill.javalib.*
import mill.javalib.publish.*
trait ProjectBaseModule extends MavenModule, PublishModule {

  def javacOptions = Opts("-source", "1.6", "-target", "1.6")

  def publishVersion = "1.0-SNAPSHOT"

  trait Tests extends MavenTests, TestModule.Junit4 {

    def mvnDeps = Seq(
      Deps.junitDep,
      Deps.hamcrestCore,
      Deps.hamcrestLibrary,
      Deps.mockitoCore
    )

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
