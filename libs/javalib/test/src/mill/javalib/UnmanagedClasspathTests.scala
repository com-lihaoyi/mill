package mill
package javalib

import mill.api.{Discover, MillException, PathRef}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object UnmanagedClasspathTests extends TestSuite {

  object WithMissingUnmanagedClasspath extends TestRootModule {
    object module extends JavaModule {
      override def unmanagedClasspath = Task {
        Seq(
          PathRef(Task.dest / "missing.jar"),
          PathRef(Task.dest / "also-missing.jar")
        )
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object WithDisabledCheck extends TestRootModule {
    object module extends JavaModule {
      override def unmanagedClasspath = Task {
        Seq(PathRef(Task.dest / "missing.jar"))
      }
      override def unmanagedClasspathExistenceCheck = Task { false }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object WithExistingUnmanagedClasspath extends TestRootModule {
    object module extends JavaModule {
      override def unmanagedClasspath = Task {
        val jar = Task.dest / "existing.jar"
        os.write(jar, "dummy content")
        Seq(PathRef(jar))
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("missingUnmanagedClasspathFails") {
      UnitTester(WithMissingUnmanagedClasspath, null).scoped { eval =>
        val Left(result) =
          eval.apply(WithMissingUnmanagedClasspath.module.localCompileClasspath).runtimeChecked
        assert(result.toString.contains("unmanagedClasspath entries do not exist"))
        assert(result.toString.contains("missing.jar"))
        assert(result.toString.contains("also-missing.jar"))
      }
    }

    test("disabledCheckAllowsMissingPaths") {
      UnitTester(WithDisabledCheck, null).scoped { eval =>
        val Right(result) =
          eval.apply(WithDisabledCheck.module.localCompileClasspath).runtimeChecked
        // Should succeed even though the path doesn't exist
        assert(result.value.exists(_.path.last == "missing.jar"))
      }
    }

    test("existingUnmanagedClasspathSucceeds") {
      UnitTester(WithExistingUnmanagedClasspath, null).scoped { eval =>
        val Right(result) =
          eval.apply(WithExistingUnmanagedClasspath.module.localCompileClasspath).runtimeChecked
        assert(result.value.exists(_.path.last == "existing.jar"))
      }
    }
  }
}
