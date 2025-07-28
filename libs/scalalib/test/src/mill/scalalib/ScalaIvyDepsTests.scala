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

  object TransitiveRunMvnDeps extends TestRootModule {
    object upstream extends JavaModule {
      def mvnDeps = Seq(mvn"org.slf4j:slf4j-api:2.0.16")
      def runMvnDeps = Seq(mvn"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure runMvnDeps are transitively picked up from normal `moduleDeps`
      def moduleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunMvnDeps2 extends TestRootModule {
    object upstream extends JavaModule {
      def mvnDeps = Seq(mvn"org.slf4j:slf4j-api:2.0.16")
      def runMvnDeps = Seq(mvn"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure both mvnDeps and runMvnDeps are transitively picked up from `runModuleDeps`
      def runModuleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object MvnDepsRepositoriesTaskDep extends TestRootModule {
    object module extends JavaModule {
      def repositoriesTask = Task.Anon {
        super.repositoriesTask() ++ Seq(
          coursier.Repositories.google
        )
      }
      // mvnDeps depends on repositoriesTask task, like can be the case sometimes
      // (like in mill-scalablytyped as of writing this). Eval'ing both tasks shouldn't
      // be a problem.
      // This used to be a problem at some point because of the
      // JavaModule#coursierProject / CoursierModule#internalRepositories stuff,
      // where repositoriesTask needed to evaluate coursierProject, itself needing mvnDeps,
      // in order to get the internal repository for Mill modules.
      // If users add a dependency the other way around, like here, this used to trigger
      // a stackoverflow. This isn't a problem anymore since the introduction of
      // CoursierModule#{allRepositories,millResolver}.
      def mvnDeps = Task {
        if (repositoriesTask().contains(coursier.Repositories.google))
          Seq(mvn"com.google.protobuf:protobuf-java:2.6.1")
        else
          Seq.empty
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("mvnDeps") - UnitTester(HelloWorldMvnDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldMvnDeps.moduleA.runClasspath): @unchecked
      assertAll(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldMvnDeps.moduleB.runClasspath): @unchecked
      assertAll(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    test("transitiveRun") - UnitTester(TransitiveRunMvnDeps, resourcePath).scoped { eval =>
      val Right(result2) = eval.apply(TransitiveRunMvnDeps.downstream.runClasspath): @unchecked

      assert(
        result2.value.exists(_.path.last == "logback-classic-1.5.10.jar")
      )
    }

    test("transitiveLocalRuntimeDepsRun") - UnitTester(TransitiveRunMvnDeps2, resourcePath).scoped {
      eval =>
        val Right(result2) = eval.apply(TransitiveRunMvnDeps2.downstream.runClasspath): @unchecked

        assertAll(
          result2.value.exists(_.path.last == "logback-classic-1.5.10.jar"),
          result2.value.exists(_.path.last == "slf4j-api-2.0.16.jar")
        )
    }

    test("mvnDepsNeedsRepositoriesTask") - UnitTester(MvnDepsRepositoriesTaskDep, null).scoped {
      eval =>
        val mvnDeps = eval.apply(MvnDepsRepositoriesTaskDep.module.mvnDeps)
          .get.fold(_.throwException, identity)
        val repositories = eval.apply(MvnDepsRepositoriesTaskDep.module.repositoriesTask)
          .get.fold(_.throwException, identity)
        assert(mvnDeps.value.contains(mvn"com.google.protobuf:protobuf-java:2.6.1"))
        assert(repositories.value.contains(coursier.Repositories.google))
    }

  }
}
