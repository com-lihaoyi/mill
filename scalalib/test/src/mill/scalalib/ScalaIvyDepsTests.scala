package mill.scalalib

import mill.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.define.Discover
object ScalaLibraryDepsTests extends TestSuite {

  object HelloWorldLibraryDeps extends TestBaseModule {
    object moduleA extends HelloWorldTests.HelloWorldModule {
      override def libraryDeps = Seq(ivy"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldTests.HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def libraryDeps = Seq(ivy"com.lihaoyi::sourcecode:0.1.4")
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunLibraryDeps extends TestBaseModule {
    object upstream extends JavaModule {
      def libraryDeps = Seq(ivy"org.slf4j:slf4j-api:2.0.16")
      def runLibraryDeps = Seq(ivy"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure runLibraryDeps are transitively picked up from normal `moduleDeps`
      def moduleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunLibraryDeps2 extends TestBaseModule {
    object upstream extends JavaModule {
      def libraryDeps = Seq(ivy"org.slf4j:slf4j-api:2.0.16")
      def runLibraryDeps = Seq(ivy"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure both libraryDeps and runLibraryDeps are transitively picked up from `runModuleDeps`
      def runModuleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object LibraryDepsRepositoriesTaskDep extends TestBaseModule {
    object module extends JavaModule {
      def repositoriesTask = Task.Anon {
        super.repositoriesTask() ++ Seq(
          coursier.Repositories.google
        )
      }
      // libraryDeps depends on repositoriesTask task, like can be the case sometimes
      // (like in mill-scalablytyped as of writing this). Eval'ing both tasks shouldn't
      // be a problem.
      // This used to be a problem at some point because of the
      // JavaModule#coursierProject / CoursierModule#internalRepositories stuff,
      // where repositoriesTask needed to evaluate coursierProject, itself needing libraryDeps,
      // in order to get the internal repository for Mill modules.
      // If users add a dependency the other way around, like here, this used to trigger
      // a stackoverflow. This isn't a problem anymore since the introduction of
      // CoursierModule#{allRepositories,millResolver}.
      def libraryDeps = Task {
        if (repositoriesTask().contains(coursier.Repositories.google))
          Agg(ivy"com.google.protobuf:protobuf-java:2.6.1")
        else
          Agg.empty
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("libraryDeps") - UnitTester(HelloWorldLibraryDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldLibraryDeps.moduleA.runClasspath): @unchecked
      assert(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldLibraryDeps.moduleB.runClasspath): @unchecked
      assert(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    test("transitiveRun") - UnitTester(TransitiveRunLibraryDeps, resourcePath).scoped { eval =>
      val Right(result2) = eval.apply(TransitiveRunLibraryDeps.downstream.runClasspath): @unchecked

      assert(
        result2.value.exists(_.path.last == "logback-classic-1.5.10.jar")
      )
    }

    test("transitiveLocalRuntimeDepsRun") - UnitTester(TransitiveRunLibraryDeps2, resourcePath).scoped {
      eval =>
        val Right(result2) = eval.apply(TransitiveRunLibraryDeps2.downstream.runClasspath): @unchecked

        assert(
          result2.value.exists(_.path.last == "logback-classic-1.5.10.jar"),
          result2.value.exists(_.path.last == "slf4j-api-2.0.16.jar")
        )
    }

    test("libraryDepsNeedsRepositoriesTask") - UnitTester(LibraryDepsRepositoriesTaskDep, null).scoped {
      eval =>
        val libraryDeps = eval.apply(LibraryDepsRepositoriesTaskDep.module.libraryDeps)
          .get.fold(_.throwException, identity)
        val repositories = eval.apply(LibraryDepsRepositoriesTaskDep.module.repositoriesTask)
          .get.fold(_.throwException, identity)
        assert(libraryDeps.value.contains(ivy"com.google.protobuf:protobuf-java:2.6.1"))
        assert(repositories.value.contains(coursier.Repositories.google))
    }

  }
}
