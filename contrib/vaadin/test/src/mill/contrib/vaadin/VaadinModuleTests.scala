package mill.contrib.vaadin

import mill.*
import mill.api.Discover
import mill.contrib.vaadin.internal.BuildInfo
import mill.javalib.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object VaadinModuleTests extends TestSuite {

  val vaadinVersion = BuildInfo.flowPluginBaseVersion

  object build extends TestRootModule {
    object app extends JavaModule with VaadinModule {
      def bomMvnDeps = Seq(mvn"com.vaadin:vaadin-bom:$vaadinVersion")
      def mvnDeps = Seq(
        mvn"com.vaadin:vaadin-core",
        // pulls junit-jupiter-engine in runtime scope only
        mvn"org.junit.jupiter:junit-jupiter:5.13.4"
      )
      def runMvnDeps = Seq(mvn"com.vaadin:vaadin-dev")

      object prod extends VaadinProdModule {
        def runMvnDeps = Seq(mvn"org.slf4j:slf4j-nop:2.0.17")
      }
    }

    object mavenApp extends MavenModule with VaadinModule

    lazy val millDiscover = Discover[this.type]
  }

  def jarNames(refs: Seq[mill.api.PathRef]): Seq[String] =
    refs.map(_.path.last).filter(_.endsWith(".jar"))

  def tests: Tests = Tests {
    test("flowVersion") - UnitTester(build, null).scoped { eval =>
      val Right(result) = eval(build.app.vaadinFlowVersion).runtimeChecked
      assert(result.value == vaadinVersion)
    }

    test("supportedFlowVersions") {
      assert(VaadinModule.isSupportedFlowVersion(BuildInfo.flowPluginBaseVersion))
      assert(VaadinModule.isSupportedFlowVersion("99.0.0"))
      assert(!VaadinModule.isSupportedFlowVersion("24.9.0"))
    }

    test("frontendDir") - UnitTester(build, null).scoped { eval =>
      val Right(millLayout) = eval(build.app.vaadinFrontendDir).runtimeChecked
      assert(millLayout.value.path == build.app.moduleDir / "frontend")
      val Right(mavenLayout) = eval(build.mavenApp.vaadinFrontendDir).runtimeChecked
      assert(mavenLayout.value.path == build.mavenApp.moduleDir / "src/main/frontend")
    }

    test("prodClasspath") - UnitTester(build, null).scoped { eval =>
      val Right(prod) = eval(build.app.prod.resolvedRunMvnDeps).runtimeChecked
      val Right(compile) = eval(build.app.compileClasspath).runtimeChecked
      val Right(run) = eval(build.app.runClasspath).runtimeChecked
      val prodJars = jarNames(prod.value)

      // the application's classes come from `app` itself, not as a module dependency
      assert(build.app.prod.moduleDeps.isEmpty)

      // dev-only tooling from the application's runMvnDeps stays out
      assert(jarNames(run.value).exists(_.startsWith("vaadin-dev-server-")))
      assert(!prodJars.exists(_.startsWith("vaadin-dev-")))

      // runtime-scoped transitive dependencies are shipped
      assert(!jarNames(compile.value).exists(_.startsWith("junit-jupiter-engine-")))
      assert(prodJars.exists(_.startsWith("junit-jupiter-engine-")))

      // the production module's own runMvnDeps are shipped, but not used by `app.run`
      val prodOnly = prodJars.filter(_.startsWith("slf4j-nop-"))
      assert(prodOnly.nonEmpty)
      assert(!jarNames(run.value).exists(_.startsWith("slf4j-nop-")))

      // everything else shipped is also on the development runtime classpath
      assert(prodJars.diff(prodOnly).toSet.subsetOf(jarNames(run.value).toSet))
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
      val Right(buildTools) = eval(build.app.vaadinDevBuildToolsDir).runtimeChecked
      val projectDir = build.app.moduleDir
      val frontendDir = build.app.moduleDir / "frontend"
      val forkArgs = result.value.toStringSeq
      assert(forkArgs.contains(s"-Dvaadin.project.basedir=$projectDir"))
      assert(forkArgs.contains(s"-D${VaadinModule.FrontendFolderProperty}=$frontendDir"))
      // module specific, and relative to the project dir, as Vaadin resolves it against that
      assert(buildTools.value.segments.contains("app"))
      val buildFolder = buildTools.value
      assert(forkArgs.contains(s"-Dvaadin.build.folder=${buildFolder.relativeTo(projectDir)}"))
    }
  }
}
