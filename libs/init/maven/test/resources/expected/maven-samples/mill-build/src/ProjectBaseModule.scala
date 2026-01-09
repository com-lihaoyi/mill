package millbuild

import mill.*
import mill.javalib.*
import mill.javalib.publish.*

trait ProjectBaseModule extends MavenModule, PublishModule {

  def javacOptions = Seq("-source", "1.6", "-target", "1.6")
  def pomSettings = Task {
    PomSettings(
      "Just a pom that makes it easy to build both projects at the same time.",
      "com.example.maven-samples",
      "",
      Seq(),
      VersionControl(None, None, None, None),
      Seq()
    )
  }

  trait ProjectBaseTests extends MavenTests, TestModule.Junit4 {

    def forkWorkingDir = moduleDir
    def mvnDeps = Seq(
      mvn"junit:junit-dep:4.10",
      mvn"org.hamcrest:hamcrest-core:1.2.1",
      mvn"org.hamcrest:hamcrest-library:1.2.1",
      mvn"org.mockito:mockito-core:1.8.5"
    )
    def testParallelism = false
    def testSandboxWorkingDir = false

  }

}
