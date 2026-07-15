package millbuild

import mill.*
import mill.javalib.*
import mill.kotlinlib.*

trait ProjectBaseModule extends KotlinMavenModule {

  def javacOptions = Seq("-source", "24", "-target", "24")

  def kotlinVersion = "2.4.0"

  trait ProjectBaseTests extends KotlinMavenTests {

    def javacOptions = Seq("-source", "24", "-target", "24")

    def forkWorkingDir = moduleDir

    def testParallelism = false

    def testSandboxWorkingDir = false

  }

}
