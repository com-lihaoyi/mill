package millbuild

import mill.*
import mill.javalib.*
import mill.javalib.publish.*

trait ProjectBaseModule extends MavenModule, PublishModule {

  def javacOptions = Seq("-source", "1.6", "-target", "1.6")

  def publishVersion = "1.0-SNAPSHOT"

  trait ProjectBaseTests extends MavenTests, TestModule.Junit4 {

    def mvnDeps = Seq(
      mvn"junit:junit-dep:4.10",
      mvn"org.hamcrest:hamcrest-core:1.2.1",
      mvn"org.hamcrest:hamcrest-library:1.2.1",
      mvn"org.mockito:mockito-core:1.8.5"
    )

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }

}
