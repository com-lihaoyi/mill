package mill.scalalib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

import HelloWorldTests._
object ScalaIvyDepsTests extends TestSuite {

  object HelloWorldIvyDeps extends TestBaseModule {
    object moduleA extends HelloWorldTests.HelloWorldModule {
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldTests.HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.4")
    }
  }

  def tests: Tests = Tests {

    test("ivyDeps") - UnitTester(HelloWorldIvyDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldIvyDeps.moduleA.runClasspath)
      assert(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldIvyDeps.moduleB.runClasspath)
      assert(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

  }
}
