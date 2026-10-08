package mill.scalalib

import mill.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.api.Discover

object ScalaMvnDepsTests extends TestSuite {

  object HelloWorldMvnDeps extends TestRootModule {
    object moduleA extends HelloWorldTests.HelloWorldModule {
      override def mvnDeps = Seq(mvn"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldTests.HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def mvnDeps = Seq(mvn"com.lihaoyi::sourcecode:0.1.4")
    }

    object forceVer extends ScalaModule {
      def scalaVersion = HelloWorldTests.scala213Version
      private val osLib = mvn"com.lihaoyi::os-lib:0.11.8"
      private val forcedOsZip = mvn"com.lihaoyi:os-zip:0.11.5"
      def resolutionParams = Task.Anon {
        super.resolutionParams().addForceVersion0(
          forcedOsZip.dep.module -> forcedOsZip.dep.versionConstraint
        )
      }

      object test extends ScalaTests with TestModule.Utest {
        def mvnDeps = Seq(osLib)
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("mvnDeps") - UnitTester(HelloWorldMvnDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldMvnDeps.moduleA.runClasspath).runtimeChecked
      assert(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldMvnDeps.moduleB.runClasspath).runtimeChecked
      assert(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    test("forceVersion in test") - UnitTester(HelloWorldMvnDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldMvnDeps.forceVer.test.runClasspath).runtimeChecked
      assert(
        result.value.exists(_.path.last == "os-zip-0.11.5.jar"),
        !result.value.exists(_.path.last == "os-zip-0.11.8.jar")
      )
    }

  }
}
