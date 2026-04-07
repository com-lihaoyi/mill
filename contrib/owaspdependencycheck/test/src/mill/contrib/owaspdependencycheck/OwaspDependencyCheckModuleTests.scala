package mill.contrib.owaspdependencycheck

import mill.contrib.owaspdependencycheck.{
  OwaspDependencyCheckJavaModule,
  OwaspDependencyCheckModule
}
import mill.testkit.{TestRootModule, UnitTester}
import utest.{TestSuite, Tests, test}
import mill.api.{Discover, PathRef, Task}
import mill.*
import mill.javalib.*
import os.{Path, pwd}

object TestModule extends TestRootModule {
  def ossScanCredentials: T[Seq[String]] = Task.Input {
    val creds = for {
      user <- Task.ctx().env.get("OSSINDEX_USERNAME")
      password <- Task.ctx().env.get("OSSINDEX_PASSWORD")
    } yield Seq("--ossIndexUsername", user, "--ossIndexPassword", password)
    creds.getOrElse(Seq.empty)
  }

  // Only run dependency checks if the OSSINDEX_USERNAME/PASSWORD is set.
  // Dependency check is historically quite fragile: because it relies on online API's that might be down or have policy changes.
  def isOssIndexCredentialsSet: T[Boolean] = Task:
    ossScanCredentials().nonEmpty

  object emptyScan extends OwaspDependencyCheckModule {
    override def owaspDependencyCheckFiles: T[Seq[PathRef]] = Seq.empty
  }
  object javaExample extends OwaspDependencyCheckJavaModule {
    override def mvnDeps: T[Seq[Dep]] = Seq(mvn"ch.qos.logback:logback-classic:1.5.12")
  }
  object failingJavaExample extends OwaspDependencyCheckJavaModule {
    override def mvnDeps: T[Seq[Dep]] = Seq(mvn"org.json:json:20230618")

    override def owaspDependencyCheckConfigArgs: T[Seq[String]] = Task {
      super.owaspDependencyCheckConfigArgs() ++ ossScanCredentials() ++ Seq("--failOnCVSS", "4")
    }
  }
  object failingJavaExampleNoTaskFail extends OwaspDependencyCheckJavaModule {
    override def mvnDeps: T[Seq[Dep]] = Seq(mvn"org.json:json:20230618")
    override def owaspDependencyCheckFailTask = false

    override def owaspDependencyCheckConfigArgs: T[Seq[String]] = Task {
      super.owaspDependencyCheckConfigArgs() ++ ossScanCredentials() ++ Seq("--failOnCVSS", "4")
    }
  }
  object examplePackageJson extends OwaspDependencyCheckModule {
    def exampleJsonGen: T[PathRef] = Task {
      os.write(
        Task.dest / "package.json",
        """{
          |  "name": "my-node-app",
          |  "version": "1.0.0",
          |  "description": "A simple Node.js app",
          |  "main": "index.js",
          |  "scripts": {
          |    "start": "node index.js"
          |  },
          |}""".stripMargin
      )
      PathRef(Task.dest / "package.json")
    }

    def owaspDependencyCheckFiles: T[Seq[PathRef]] = Task { Seq(exampleJsonGen()) }
  }

  lazy val millDiscover = Discover[this.type]
}
class OwaspDependencyCheckModuleTests extends TestSuite {
  override def tests = Tests {
    def runIfOssIsEnabled(eval: UnitTester)(runTest: => Unit): Unit = {
      val Right(isOssCredentialsSet) =
        eval.apply(TestModule.isOssIndexCredentialsSet).runtimeChecked
      if (isOssCredentialsSet.value) {
        runTest
      } else {
        println(
          "Dependency check tests skipped. Define OSSINDEX_USERNAME and OSSINDEX_PASSWORD to run them"
        )
      }
    }
    test("Run successful dependency scans") - UnitTester(
      TestModule,
      null
    ).scoped { eval =>
      runIfOssIsEnabled(eval) {
        val Right(resultEmpty) =
          eval.apply(TestModule.emptyScan.owaspDependencyCheck()).runtimeChecked
        assert(resultEmpty.value.success)
        val Right(resultJavaExample) =
          eval.apply(TestModule.javaExample.owaspDependencyCheck()).runtimeChecked
        assert(resultJavaExample.value.success)
        val Right(resultPackageJsonExample) =
          eval.apply(TestModule.examplePackageJson.owaspDependencyCheck()).runtimeChecked
        assert(resultPackageJsonExample.value.success)
      }
    }
    test("Run failing dependency scans") - UnitTester(
      TestModule,
      null
    ).scoped { eval =>
      runIfOssIsEnabled(eval) {
        val Left(error) =
          eval.apply(TestModule.failingJavaExample.owaspDependencyCheck()).runtimeChecked
        val Right(suppressedFailure) =
          eval.apply(TestModule.failingJavaExampleNoTaskFail.owaspDependencyCheck()).runtimeChecked
        assert(!suppressedFailure.value.success)
        assert(suppressedFailure.value.reportFiles.size == 1)
      }
    }
  }

}
