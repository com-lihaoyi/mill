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

  object TransitiveRunIvyDeps extends TestBaseModule {
    object upstream extends JavaModule {
      def ivyDeps = Agg(ivy"org.slf4j:slf4j-api:2.0.16")
      def runIvyDeps = Agg(ivy"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure runIvyDeps are transitively picked up from normal `moduleDeps`
      def moduleDeps = Seq(upstream)
    }
  }

  object TransitiveRunIvyDeps2 extends TestBaseModule {
    object upstream extends JavaModule {
      def ivyDeps = Agg(ivy"org.slf4j:slf4j-api:2.0.16")
      def runIvyDeps = Agg(ivy"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure both ivyDeps and runIvyDeps are transitively picked up from `runModuleDeps`
      def runModuleDeps = Seq(upstream)
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

    test("transitiveRun") - UnitTester(TransitiveRunIvyDeps, resourcePath).scoped { eval =>
      val Right(result2) = eval.apply(TransitiveRunIvyDeps.downstream.runClasspath)

      assert(
        result2.value.exists(_.path.last == "logback-classic-1.5.10.jar")
      )
    }

    test("transitiveLocalRuntimeDepsRun") - UnitTester(TransitiveRunIvyDeps2, resourcePath).scoped {
      eval =>
        val Right(result2) = eval.apply(TransitiveRunIvyDeps2.downstream.runClasspath)

        assert(
          result2.value.exists(_.path.last == "logback-classic-1.5.10.jar"),
          result2.value.exists(_.path.last == "slf4j-api-2.0.16.jar")
        )
    }

  }
}
