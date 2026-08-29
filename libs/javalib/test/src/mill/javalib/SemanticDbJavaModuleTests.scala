package mill.javalib

import mill.*
import mill.api.{Discover, Evaluator, ExecResult}
import mill.api.daemon.internal.SemanticDbJavaModuleApi
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object SemanticDbJavaModuleTests extends TestSuite {

  object VersionModules extends TestRootModule {
    object defaults extends JavaModule

    object pinned extends JavaModule {
      override def semanticDbVersion: T[String] = Task { "1.2.3" }
      override def semanticDbJavaVersion: T[String] = Task { "4.5.6" }
    }

    object missingJavaVersion extends JavaModule {
      override def semanticDbJavaVersion: T[String] = Task { "" }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("versions") {
      test("defaults") - UnitTester(VersionModules, null).scoped { eval =>
        val Right(scalaVersion) =
          eval.apply(VersionModules.defaults.semanticDbVersion).runtimeChecked
        val Right(javaVersion) =
          eval.apply(VersionModules.defaults.semanticDbJavaVersion).runtimeChecked

        assert(
          scalaVersion.value == SemanticDbJavaModuleApi.buildTimeSemanticDbVersion,
          javaVersion.value == SemanticDbJavaModuleApi.buildTimeJavaSemanticDbVersion
        )
      }

      test("environmentRequestsMinimumVersion") - UnitTester(
        VersionModules,
        null,
        env = Evaluator.defaultEnv ++ Map(
          "SEMANTICDB_VERSION" -> "999.0.0",
          "JAVASEMANTICDB_VERSION" -> "0.0.1"
        )
      ).scoped { eval =>
        val Right(scalaVersion) =
          eval.apply(VersionModules.defaults.semanticDbVersion).runtimeChecked
        val Right(javaVersion) =
          eval.apply(VersionModules.defaults.semanticDbJavaVersion).runtimeChecked

        assert(
          scalaVersion.value == "999.0.0",
          javaVersion.value == SemanticDbJavaModuleApi.buildTimeJavaSemanticDbVersion
        )
      }

      test("explicitOverridesPinVersion") - UnitTester(
        VersionModules,
        null,
        env = Evaluator.defaultEnv ++ Map(
          "SEMANTICDB_VERSION" -> "999.0.0",
          "JAVASEMANTICDB_VERSION" -> "999.0.0"
        )
      ).scoped { eval =>
        val Right(scalaVersion) = eval.apply(VersionModules.pinned.semanticDbVersion).runtimeChecked
        val Right(javaVersion) =
          eval.apply(VersionModules.pinned.semanticDbJavaVersion).runtimeChecked

        assert(scalaVersion.value == "1.2.3", javaVersion.value == "4.5.6")
      }
    }

    test("missingJavaVersionError") - UnitTester(VersionModules, null).scoped { eval =>
      val Left(ExecResult.Failure(msg = message)) =
        eval.apply(VersionModules.missingJavaVersion.semanticDbData).runtimeChecked

      assert(message.contains("You must provide a semanticDbJavaVersion"))
    }
  }
}
