package mill.scalalib

import mill.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.define.Discover
object ScalaJvmDepsTests extends TestSuite {

  object HelloWorldJvmDeps extends TestBaseModule {
    object moduleA extends HelloWorldTests.HelloWorldModule {
      override def jvmDeps = Seq(jvm"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldTests.HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def jvmDeps = Seq(jvm"com.lihaoyi::sourcecode:0.1.4")
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunJvmDeps extends TestBaseModule {
    object upstream extends JavaModule {
      def jvmDeps = Seq(jvm"org.slf4j:slf4j-api:2.0.16")
      def runJvmDeps = Seq(jvm"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure runJvmDeps are transitively picked up from normal `moduleDeps`
      def moduleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TransitiveRunJvmDeps2 extends TestBaseModule {
    object upstream extends JavaModule {
      def jvmDeps = Seq(jvm"org.slf4j:slf4j-api:2.0.16")
      def runJvmDeps = Seq(jvm"ch.qos.logback:logback-classic:1.5.10")
    }

    object downstream extends JavaModule {
      // Make sure both jvmDeps and runJvmDeps are transitively picked up from `runModuleDeps`
      def runModuleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object JvmDepsRepositoriesTaskDep extends TestBaseModule {
    object module extends JavaModule {
      def repositoriesTask = Task.Anon {
        super.repositoriesTask() ++ Seq(
          coursier.Repositories.google
        )
      }
      // jvmDeps depends on repositoriesTask task, like can be the case sometimes
      // (like in mill-scalablytyped as of writing this). Eval'ing both tasks shouldn't
      // be a problem.
      // This used to be a problem at some point because of the
      // JavaModule#coursierProject / CoursierModule#internalRepositories stuff,
      // where repositoriesTask needed to evaluate coursierProject, itself needing jvmDeps,
      // in order to get the internal repository for Mill modules.
      // If users add a dependency the other way around, like here, this used to trigger
      // a stackoverflow. This isn't a problem anymore since the introduction of
      // CoursierModule#{allRepositories,millResolver}.
      def jvmDeps = Task {
        if (repositoriesTask().contains(coursier.Repositories.google))
          Agg(jvm"com.google.protobuf:protobuf-java:2.6.1")
        else
          Agg.empty
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("jvmDeps") - UnitTester(HelloWorldJvmDeps, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(HelloWorldJvmDeps.moduleA.runClasspath): @unchecked
      assert(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldJvmDeps.moduleB.runClasspath): @unchecked
      assert(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    test("transitiveRun") - UnitTester(TransitiveRunJvmDeps, resourcePath).scoped { eval =>
      val Right(result2) = eval.apply(TransitiveRunJvmDeps.downstream.runClasspath): @unchecked

      assert(
        result2.value.exists(_.path.last == "logback-classic-1.5.10.jar")
      )
    }

    test("transitiveLocalRuntimeDepsRun") - UnitTester(TransitiveRunJvmDeps2, resourcePath).scoped {
      eval =>
        val Right(result2) = eval.apply(TransitiveRunJvmDeps2.downstream.runClasspath): @unchecked

        assert(
          result2.value.exists(_.path.last == "logback-classic-1.5.10.jar"),
          result2.value.exists(_.path.last == "slf4j-api-2.0.16.jar")
        )
    }

    test("jvmDepsNeedsRepositoriesTask") - UnitTester(JvmDepsRepositoriesTaskDep, null).scoped {
      eval =>
        val jvmDeps = eval.apply(JvmDepsRepositoriesTaskDep.module.jvmDeps)
          .get.fold(_.throwException, identity)
        val repositories = eval.apply(JvmDepsRepositoriesTaskDep.module.repositoriesTask)
          .get.fold(_.throwException, identity)
        assert(jvmDeps.value.contains(jvm"com.google.protobuf:protobuf-java:2.6.1"))
        assert(repositories.value.contains(coursier.Repositories.google))
    }

  }
}
