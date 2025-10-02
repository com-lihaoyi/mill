package mill.scalalib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

import HelloWorldTests._
object ScalaIvyDepsTests extends TestSuite {

  object HelloWorldIvyDeps extends TestBaseModule {
    object moduleA extends HelloWorldTests.HelloWorldModule {
      override def ivyDeps = Agg(mvn"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldTests.HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def ivyDeps = Agg(mvn"com.lihaoyi::sourcecode:0.1.4")
    }
  }

  object TransitiveRunIvyDeps extends TestBaseModule {
    object upstream extends JavaModule {
      def ivyDeps = Agg(mvn"org.slf4j:slf4j-api:2.0.16")
      def runIvyDeps = Agg(mvn"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure runIvyDeps are transitively picked up from normal `moduleDeps`
      def moduleDeps = Seq(upstream)
    }
  }

  object TransitiveRunIvyDeps2 extends TestBaseModule {
    object upstream extends JavaModule {
      def ivyDeps = Agg(mvn"org.slf4j:slf4j-api:2.0.16")
      def runIvyDeps = Agg(mvn"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure both ivyDeps and runIvyDeps are transitively picked up from `runModuleDeps`
      def runModuleDeps = Seq(upstream)
    }
  }

  object IvyDepsRepositoriesTaskDep extends TestBaseModule {
    object module extends JavaModule {
      def repositoriesTask = Task.Anon {
        super.repositoriesTask() ++ Seq(
          coursier.Repositories.google
        )
      }
      // ivyDeps depends on repositoriesTask task, like can be the case sometimes
      // (like in mill-scalablytyped as of writing this). Eval'ing both tasks shouldn't
      // be a problem.
      // This used to be a problem at some point because of the
      // JavaModule#coursierProject / CoursierModule#internalRepositories stuff,
      // where repositoriesTask needed to evaluate coursierProject, itself needing ivyDeps,
      // in order to get the internal repository for Mill modules.
      // If users add a dependency the other way around, like here, this used to trigger
      // a stackoverflow. This isn't a problem anymore since the introduction of
      // CoursierModule#{allRepositories,millResolver}.
      def ivyDeps = Task {
        if (repositoriesTask().contains(coursier.Repositories.google))
          Agg(mvn"com.google.protobuf:protobuf-java:2.6.1")
        else
          Agg.empty
      }
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

    test("ivyDepsNeedsRepositoriesTask") - UnitTester(IvyDepsRepositoriesTaskDep, null).scoped {
      eval =>
        val ivyDeps = eval.apply(IvyDepsRepositoriesTaskDep.module.ivyDeps).toTry.get
        val repositories = eval.apply(IvyDepsRepositoriesTaskDep.module.repositoriesTask).toTry.get
        assert(ivyDeps.value.contains(mvn"com.google.protobuf:protobuf-java:2.6.1"))
        assert(repositories.value.contains(coursier.Repositories.google))
    }

  }
}
