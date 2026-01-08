package millbuild

import mill.*
import mill.javalib.*
import mill.javalib.errorprone.ErrorProneModule

trait ProjectBaseModule extends MavenModule {

  def depManagement = Seq(mvn"org.apache.commons:commons-text:1.9")
  def javacOptions = Seq("-source", "17", "-target", "17")

  trait ProjectBaseTests extends MavenTests {

    def forkWorkingDir = moduleDir
    def mvnDeps = Seq(mvn"org.junit.jupiter:junit-jupiter:5.9.1")
    def runMvnDeps = Seq(mvn"org.junit.platform:junit-platform-launcher")
    def bomMvnDeps = Seq(mvn"org.junit:junit-bom:5.9.1")
    def javacOptions = Seq("-source", "17", "-target", "17")
    def testParallelism = false
    def testSandboxWorkingDir = false

  }

}
