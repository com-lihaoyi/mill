package millbuild

import mill.*
import mill.javalib.*
import mill.kotlinlib.*

trait ProjectBaseModule extends KotlinMavenModule {

  def mvnDeps = Seq(mvn"org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
  def javacOptions = Seq("-source", "24", "-target", "24")
  def kotlinVersion = "2.4.0"

  trait ProjectBaseTests extends KotlinMavenTests {

    def forkWorkingDir = moduleDir
    def javacOptions = Seq("-source", "24", "-target", "24")
    def testParallelism = false
    def testSandboxWorkingDir = false

  }

}
