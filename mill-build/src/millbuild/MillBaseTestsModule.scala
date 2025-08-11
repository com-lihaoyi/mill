package millbuild

import mill.*
import mill.scalalib.*

trait MillBaseTestsModule extends TestModule {
  def forkArgs = Task {
    Seq(
      s"-DMILL_SCALA_3_NEXT_VERSION=${Deps.scalaVersion}",
      s"-DMILL_SCALA_2_13_VERSION=${Deps.scala2Version}",
      s"-DMILL_SCALA_2_12_VERSION=${Deps.workerScalaVersion212}",
      s"-DTEST_SCALA_2_13_VERSION=${Deps.testScala213Version}",
      s"-DTEST_SCALA_2_13_VERSION_FOR_SCALANATIVE_4_2=${Deps.testScala213VersionForScalaNative42}",
      s"-DTEST_SCALA_2_12_VERSION=${Deps.testScala212Version}",
      s"-DTEST_SCALA_3_2_VERSION=${Deps.testScala32Version}",
      s"-DTEST_SCALA_3_3_VERSION=${Deps.testScala33Version}",
      s"-DTEST_SCALAJS_VERSION=${Deps.Scalajs_1.scalaJsVersion}",
      s"-DTEST_SCALANATIVE_0_5_VERSION=${Deps.Scalanative_0_5.scalanativeVersion}",
      s"-DTEST_UTEST_VERSION=${Deps.TestDeps.utest.version}",
      s"-DTEST_SCALATEST_VERSION=${Deps.TestDeps.scalaTest.version}",
      s"-DTEST_TEST_INTERFACE_VERSION=${Deps.sbtTestInterface.version}",
      s"-DTEST_ZIOTEST_VERSION=${Deps.TestDeps.zioTest.version}",
      s"-DTEST_ZINC_VERSION=${Deps.zinc.version}",
      s"-DTEST_KOTLIN_VERSION=${Deps.kotlinCompiler.version}",
      s"-DTEST_SBT_VERSION=${Deps.sbt.version}",
      s"-DTEST_PROGUARD_VERSION=${Deps.RuntimeDeps.proguard.version}",
      s"-DTEST_KOTEST_VERSION=${Deps.RuntimeDeps.kotestJvm.version}"
    )
  }

  def testFramework = "mill.api.UTestFramework"
  def testParallelism = true
}
