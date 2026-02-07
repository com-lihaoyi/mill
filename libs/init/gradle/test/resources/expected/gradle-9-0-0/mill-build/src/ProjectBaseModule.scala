package millbuild

import mill.*
import mill.javalib.*
import mill.javalib.publish.*

trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(mvn"org.apache.commons:commons-text:1.13.0")
  def javacOptions = Seq("-source", "21", "-target", "21")

  trait ProjectBaseTests extends MavenTests, TestModule.Junit5 {

    def forkWorkingDir = moduleDir
    def mvnDeps = Seq(mvn"org.junit.jupiter:junit-jupiter:5.12.1")
    def runMvnDeps = Seq(mvn"org.junit.platform:junit-platform-launcher")
    def bomMvnDeps = Seq(mvn"org.junit:junit-bom:5.12.1")
    def javacOptions = Seq("-source", "21", "-target", "21")
    def testParallelism = false
    def testSandboxWorkingDir = false

  }

}
