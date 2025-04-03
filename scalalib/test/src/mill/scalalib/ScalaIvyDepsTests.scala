package mill.scalalib

import mill.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.define.Discover
object ScalaIvyDepsTests extends TestSuite {

  object HelloWorldIvyDeps extends TestBaseModule {
    object moduleA extends HelloWorldTests.HelloWorldModule {
      override def ivyDeps = Seq(ivy"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldTests.HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def ivyDeps = Seq(ivy"com.lihaoyi::sourcecode:0.1.4")
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunIvyDeps extends TestBaseModule {
    object upstream extends JavaModule {
      def ivyDeps = Seq(ivy"org.slf4j:slf4j-api:2.0.16")
      def runIvyDeps = Seq(ivy"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure runIvyDeps are transitively picked up from normal `moduleDeps`
      def moduleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunIvyDeps2 extends TestBaseModule {
    object upstream extends JavaModule {
      def ivyDeps = Seq(ivy"org.slf4j:slf4j-api:2.0.16")
      def runIvyDeps = Seq(ivy"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure both ivyDeps and runIvyDeps are transitively picked up from `runModuleDeps`
      def runModuleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object IvyDepsRepositoriesDep extends TestBaseModule {
    object module extends JavaModule {
      def repositories = Task.Anon {
        super.repositories() ++ Seq(
          coursier.Repositories.google
        )
      }
      // ivyDeps depends on repositories task, like can be the case sometimes
      // (like in mill-scalablytyped as of writing this). Eval'ing both tasks shouldn't
      // be a problem.
      // This used to be a problem at some point because of the
      // JavaModule#coursierProject / CoursierModule#internalRepositories stuff,
      // where repositories needed to evaluate coursierProject, itself needing ivyDeps,
      // in order to get the internal repository for Mill modules.
      // If users add a dependency the other way around, like here, this used to trigger
      // a stackoverflow. This isn't a problem anymore since the introduction of
      // CoursierModule#{allRepositories,millResolver}.
      def ivyDeps = Task {
        if (repositories().contains(coursier.Repositories.google))
          Agg(ivy"com.google.protobuf:protobuf-java:2.6.1")
        else
          Agg.empty
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("ivyDeps") - UnitTester(HelloWorldIvyDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldIvyDeps.moduleA.runClasspath): @unchecked
      assert(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldIvyDeps.moduleB.runClasspath): @unchecked
      assert(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    test("transitiveRun") - UnitTester(TransitiveRunIvyDeps, resourcePath).scoped { eval =>
      val Right(result2) = eval.apply(TransitiveRunIvyDeps.downstream.runClasspath): @unchecked

      assert(
        result2.value.exists(_.path.last == "logback-classic-1.5.10.jar")
      )
    }

    test("transitiveLocalRuntimeDepsRun") - UnitTester(TransitiveRunIvyDeps2, resourcePath).scoped {
      eval =>
        val Right(result2) = eval.apply(TransitiveRunIvyDeps2.downstream.runClasspath): @unchecked

        assert(
          result2.value.exists(_.path.last == "logback-classic-1.5.10.jar"),
          result2.value.exists(_.path.last == "slf4j-api-2.0.16.jar")
        )
    }

    test("ivyDepsNeedsrepositories") - UnitTester(IvyDepsRepositoriesDep, null).scoped {
      eval =>
        val ivyDeps = eval.apply(IvyDepsRepositoriesDep.module.ivyDeps)
          .get.fold(_.throwException, identity)
        val repositories = eval.apply(IvyDepsRepositoriesDep.module.repositories)
          .get.fold(_.throwException, identity)
        assert(ivyDeps.value.contains(ivy"com.google.protobuf:protobuf-java:2.6.1"))
        assert(repositories.value.contains(coursier.Repositories.google))
    }

  }
}
