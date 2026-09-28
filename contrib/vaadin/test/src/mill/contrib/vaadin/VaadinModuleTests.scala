package mill.contrib.vaadin

import mill.*
import mill.api.Discover
import mill.javalib.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object VaadinModuleTests extends TestSuite {

  val vaadinVersion = sys.props("MILL_VAADIN_VERSION")

  object build extends TestRootModule {
    object app extends JavaModule with VaadinModule {
      def bomMvnDeps = Seq(mvn"com.vaadin:vaadin-bom:$vaadinVersion")
      def mvnDeps = Seq(
        mvn"com.vaadin:vaadin-core",
        // pulls junit-jupiter-engine in runtime scope only
        mvn"org.junit.jupiter:junit-jupiter:5.13.4"
      )
      def runMvnDeps = Seq(mvn"com.vaadin:vaadin-dev")
    }

    lazy val millDiscover = Discover[this.type]
  }

  def jarNames(refs: Seq[mill.api.PathRef]): Seq[String] =
    refs.map(_.path.last).filter(_.endsWith(".jar"))

  def tests: Tests = Tests {
    test("flowVersion") - UnitTester(build, null).scoped { eval =>
      val Right(result) = eval(build.app.vaadinFlowVersion).runtimeChecked
      assert(result.value == vaadinVersion)
    }

    test("prodClasspath") - UnitTester(build, null).scoped { eval =>
      val Right(prod) = eval(build.app.vaadinProdMvnClasspath).runtimeChecked
      val Right(compile) = eval(build.app.compileClasspath).runtimeChecked
      val Right(run) = eval(build.app.runClasspath).runtimeChecked
      val prodJars = jarNames(prod.value)

      // dev-only tooling from runMvnDeps stays out
      assert(jarNames(run.value).exists(_.startsWith("vaadin-dev-server-")))
      assert(!prodJars.exists(_.startsWith("vaadin-dev-")))

      // runtime-scoped transitive dependencies are shipped
      assert(!jarNames(compile.value).exists(_.startsWith("junit-jupiter-engine-")))
      assert(prodJars.exists(_.startsWith("junit-jupiter-engine-")))

      // everything shipped is also on the regular runtime classpath
      assert(prodJars.toSet.subsetOf(jarNames(run.value).toSet))
    }

    test("isolatedWorker") - UnitTester(build, null).scoped { eval =>
      val Right(worker) = eval(build.app.vaadinWorker).runtimeChecked
      val workerLoader = worker.value.getClass.getClassLoader
      assert(workerLoader != getClass.getClassLoader)
      // Vaadin's tooling is only visible inside the worker, in the app's Flow version
      val version = workerLoader.loadClass("com.vaadin.flow.server.Version")
        .getMethod("getFullVersion").invoke(null)
      assert(version == vaadinVersion)
      assertThrows[ClassNotFoundException] {
        getClass.getClassLoader.loadClass("com.vaadin.flow.plugin.base.BuildFrontendUtil")
      }
    }

    test("devModeForkArgs") - UnitTester(build, null).scoped { eval =>
      val Right(result) = eval(build.app.forkArgs).runtimeChecked
      val projectDir = VaadinModule.realPath(build.app.moduleDir)
      assert(result.value.contains(s"-Dvaadin.project.basedir=$projectDir"))
      // relative to the project dir, as Vaadin resolves it against that
      val buildFolder = VaadinModule.realPath(build.app.vaadinBuildToolsDir)
      assert(result.value.contains(s"-Dvaadin.build.folder=${projectDir.relativize(buildFolder)}"))
    }
  }
}
