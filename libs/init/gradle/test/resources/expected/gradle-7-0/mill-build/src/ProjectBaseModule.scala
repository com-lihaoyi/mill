package millbuild

import mill.*
import mill.javalib.*

trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(mvn"org.apache.commons:commons-text:1.9")
  def javacOptions = Seq("-source", "11", "-target", "11")

  trait ProjectBaseTests extends MavenTests, TestModule.Junit5 {

    def forkWorkingDir = moduleDir
    def mandatoryMvnDeps =
      Seq(mvn"com.github.sbt.junit:jupiter-interface:0.11.4")
    def mvnDeps = Seq(mvn"org.junit.jupiter:junit-jupiter-api:5.7.1")
    def runMvnDeps = Seq(
      mvn"org.junit.jupiter:junit-jupiter-engine",
      mvn"org.junit.platform:junit-platform-launcher"
    )
    def bomMvnDeps = Seq(mvn"org.junit:junit-bom:5.7.1")
    def javacOptions = Seq("-source", "11", "-target", "11")
    def testParallelism = false
    def testSandboxWorkingDir = false

  }

}
