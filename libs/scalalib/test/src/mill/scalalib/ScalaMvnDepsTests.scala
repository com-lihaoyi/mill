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

  }
}
