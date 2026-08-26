package mill
package javalib

import mill.api.Discover
import mill.api.PathRef
import mill.api.daemon.internal.bsp.BspUri
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

// Tests that the `jvm` build target data a module reports over BSP describes the JDK that module
// is built against - both fields of it, not just `javaHome`
object BspJvmBuildTargetTests extends TestSuite {

  /**
   * A directory shaped like a JDK home, as far as anything reading a JDK's version is concerned.
   *
   * Using a real JDK would mean downloading one, and asserting on a version that is only right
   * until that JDK is updated. The version here is one no JVM will ever report, so a `javaVersion`
   * that leaks in from the JVM running these tests cannot be mistaken for the expected value.
   */
  private lazy val fakeJavaHome = {
    val dir = os.temp.dir(prefix = "fake-java-home", deleteOnExit = true)
    os.write(
      dir / "release",
      """IMPLEMENTOR="Fake"
        |JAVA_VERSION="1.2.3-fake"
        |""".stripMargin
    )
    dir
  }

  object BspJvmBuildTarget extends TestRootModule {
    object withoutJavaHome extends JavaModule
    object withJavaHome extends JavaModule {
      override def javaHome = Task { Some(PathRef(fakeJavaHome)) }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("moduleJdk") {
      UnitTester(BspJvmBuildTarget, null).scoped { eval =>
        val Right(result) =
          eval.apply(BspJvmBuildTarget.withJavaHome.bspJvmBuildTargetTask).runtimeChecked
        val jvmBuildTarget = result.value
        assert(jvmBuildTarget.javaHome == Some(BspUri(fakeJavaHome.toNIO)))
        // Not the version of the JVM running Mill, which is what `javaHome` above is *not*
        assert(jvmBuildTarget.javaVersion == Some("1.2.3-fake"))
      }
    }

    test("millJdkWhenModuleHasNone") {
      UnitTester(BspJvmBuildTarget, null).scoped { eval =>
        val Right(result) =
          eval.apply(BspJvmBuildTarget.withoutJavaHome.bspJvmBuildTargetTask).runtimeChecked
        val jvmBuildTarget = result.value
        assert(jvmBuildTarget.javaHome == Some(BspUri(os.Path(sys.props("java.home")).toNIO)))
        assert(jvmBuildTarget.javaVersion == Some(sys.props("java.version")))
      }
    }
  }
}
