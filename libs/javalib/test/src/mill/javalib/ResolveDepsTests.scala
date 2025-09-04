package mill.javalib

import coursier.core.VariantSelector.VariantMatcher
import coursier.maven.MavenRepository
import coursier.params.ResolutionParams
import mill.api.Result.{Failure, Success}
import mill.api.{PathRef}
import mill.api.{Result}

import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.CoursierConfig
import mill.util.TokenReaders._
object ResolveDepsTests extends TestSuite {
  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val repos =
    Seq(coursier.LocalRepositories.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))

  def evalDeps(deps: Seq[Dep]): Result[Seq[PathRef]] = Lib.resolveDependencies(
    repos,
    deps.map(Lib.depToBoundDep(_, scala212Version, "")),
    config = CoursierConfig.default()
  )

  def assertRoundTrip(deps: Seq[Dep], simplified: Boolean) = {
    for (dep <- deps) {
      val unparsed = Dep.unparse(dep)
      if (simplified) {
        assert(unparsed.nonEmpty)
        assert(Dep.parse(unparsed.get) == dep)
      } else {
        assert(unparsed.isEmpty)
      }
      assert(upickle.read[Dep](upickle.write(dep)) == dep)
    }
  }

  object TestCase extends TestRootModule {
    object pomStuff extends JavaModule {
      def mvnDeps = Seq(
        // Dependency whose packaging is "pom", as it's meant to be used
        // as a "parent dependency" by other dependencies, rather than be pulled
        // as we do here. We do it anyway, to check that pulling the "pom" artifact
        // type brings that dependency POM file in the class path. We need a dependency
        // that has a "pom" packaging for that.
        mvn"org.apache.hadoop:hadoop-yarn-server:3.4.0"
      )
      def artifactTypes = super.artifactTypes() + coursier.Type("pom")
    }

    object scope extends JavaModule {
      def mvnDeps = Seq(
        mvn"androidx.compose.animation:animation-core:1.1.1",
        mvn"androidx.compose.ui:ui:1.1.1"
      )
      def repositoriesTask = Task.Anon {
        super.repositoriesTask() :+ coursier.Repositories.google
      }
      def artifactTypes = super.artifactTypes() + coursier.core.Type("aar")

      def resolutionParams = Task.Anon {
        super.resolutionParams().addVariantAttributes(
          "org.jetbrains.kotlin.platform.type" -> VariantMatcher.Equals("jvm")
        )
      }
    }

    object optional extends JavaModule {
      def mvnDeps = Seq(
        mvn"io.get-coursier:interface:1.0.29-M1".optional()
      )
      def compileMvnDeps = Seq(
        mvn"com.lihaoyi:sourcecode_3:0.4.3-M5".optional()
      )
      def runMvnDeps = Seq(
        mvn"ch.qos.logback:logback-core:1.5.18".optional()
      )

      object dependsOnOptional extends JavaModule {
        def moduleDeps = Seq(optional)
      }

      // Just make sure all of these compiles with empty `Seq()` or `Nil`
      // as the RHS of these task methods
      object empty extends JavaModule {
        def mvnDeps = Seq()
        def compileMvnDeps = Nil
        def runMvnDeps = Task { Seq() }
        def bomMvnDeps = Task { Nil }
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  val tests = Tests {
    test("resolveValidDeps") {
      val deps = Seq(mvn"com.lihaoyi::pprint:0.5.3")
      val Success(paths) = evalDeps(deps): @unchecked
      assert(paths.nonEmpty)
    }

    test("resolveValidDepsWithClassifier") {
      val deps = Seq(mvn"org.lwjgl:lwjgl:3.1.1;classifier=natives-macos")
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(paths.nonEmpty)
      assert(paths.head.path.toString.contains("natives-macos"))
    }

    test("resolveTransitiveRuntimeDeps") {
      val deps = Seq(mvn"org.mockito:mockito-core:2.7.22")
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(paths.nonEmpty)
      assert(paths.exists(_.path.toString.contains("objenesis")))
      assert(paths.exists(_.path.toString.contains("byte-buddy")))
    }

    test("excludeTransitiveDeps") {
      val deps = Seq(mvn"com.lihaoyi::pprint:0.5.3".exclude("com.lihaoyi" -> "fansi_2.12"))
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    test("excludeTransitiveDepsByOrg") {
      val deps = Seq(mvn"com.lihaoyi::pprint:0.5.3".excludeOrg("com.lihaoyi"))
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(!paths.exists(path =>
        path.path.toString.contains("com/lihaoyi") && !path.path.toString.contains("pprint_2.12")
      ))
    }

    test("excludeTransitiveDepsByName") {
      val deps = Seq(mvn"com.lihaoyi::pprint:0.5.3".excludeName("fansi_2.12"))
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    test("errOnInvalidOrgDeps") {
      val deps = Seq(mvn"xxx.yyy.invalid::pprint:0.5.3")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg) = evalDeps(deps): @unchecked
      assert(errMsg.contains("xxx.yyy.invalid"))
    }

    test("errOnInvalidVersionDeps") {
      val deps = Seq(mvn"com.lihaoyi::pprint:invalid.version.num")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg) = evalDeps(deps): @unchecked
      assert(errMsg.contains("invalid.version.num"))
    }

    test("errOnPartialSuccess") {
      val deps = Seq(mvn"com.lihaoyi::pprint:0.5.3", mvn"fake::fake:fake")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg) = evalDeps(deps): @unchecked
      assert(errMsg.contains("fake"))
    }

    test("pomArtifactType") {
      val sources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "pomArtifactType"
      UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
        val Right(compileResult) = eval(TestCase.pomStuff.compileClasspath): @unchecked
        val compileCp = compileResult.value.toSeq.map(_.path)
        assert(compileCp.exists(_.lastOpt.contains("hadoop-yarn-server-3.4.0.pom")))

        val Right(runResult) = eval(TestCase.pomStuff.runClasspath): @unchecked
        val runCp = runResult.value.toSeq.map(_.path)
        assert(runCp.exists(_.lastOpt.contains("hadoop-yarn-server-3.4.0.pom")))
      }
    }

    test("scopes") {
      UnitTester(TestCase, null).scoped { eval =>
        val compileCp = eval(TestCase.scope.compileClasspath)
          .fold(_.get, _.value).map(_.path)
        val runtimeCp = eval(TestCase.scope.upstreamAssemblyClasspath)
          .fold(_.get, _.value).map(_.path)
        val runCp = eval(TestCase.scope.runClasspath)
          .fold(_.get, _.value).map(_.path)

        val runtimeOnlyJars = Seq(
          "lifecycle-common-2.3.0.jar",
          "lifecycle-runtime-2.3.0.aar"
        )
        for (runtimeOnlyJar <- runtimeOnlyJars) {
          assert(!compileCp.exists(_.last == runtimeOnlyJar))
          assert(runtimeCp.exists(_.last == runtimeOnlyJar))
          assert(runCp.exists(_.last == runtimeOnlyJar))
        }
      }
    }

    test("optional") {
      UnitTester(TestCase, null).scoped { eval =>
        val optionalCompileCp = eval(TestCase.optional.compileClasspath)
          .fold(_.get, _.value).map(_.path)
        val optionalRuntimeCp = eval(TestCase.optional.upstreamAssemblyClasspath)
          .fold(_.get, _.value).map(_.path)
        val optionalRunCp = eval(TestCase.optional.runClasspath)
          .fold(_.get, _.value).map(_.path)
        val dependsOnOptionalCompileCp = eval(TestCase.optional.dependsOnOptional.compileClasspath)
          .fold(_.get, _.value).map(_.path)
        val dependsOnOptionalRuntimeCp =
          eval(TestCase.optional.dependsOnOptional.upstreamAssemblyClasspath)
            .fold(_.get, _.value).map(_.path)
        val dependsOnOptionalRunCp = eval(TestCase.optional.dependsOnOptional.runClasspath)
          .fold(_.get, _.value).map(_.path)

        // optional dependencies in mvnDeps should be added to the current module class path
        assert(optionalCompileCp.exists(_.last == "interface-1.0.29-M1.jar"))
        assert(optionalRuntimeCp.exists(_.last == "interface-1.0.29-M1.jar"))
        assert(optionalRunCp.exists(_.last == "interface-1.0.29-M1.jar"))

        // optional dependencies in compileMvnDeps should be added to the current module compile class path,
        // but not to the runtime class path
        assert(optionalCompileCp.exists(_.last == "sourcecode_3-0.4.3-M5.jar"))
        assert(!optionalRuntimeCp.exists(_.last == "sourcecode_3-0.4.3-M5.jar"))
        assert(!optionalRunCp.exists(_.last == "sourcecode_3-0.4.3-M5.jar"))

        // optional dependencies in runMvnDeps should be added to the current module run class path,
        // but not to the compile class path
        assert(!optionalCompileCp.exists(_.last == "logback-core-1.5.18.jar"))
        assert(optionalRuntimeCp.exists(_.last == "logback-core-1.5.18.jar"))
        assert(optionalRunCp.exists(_.last == "logback-core-1.5.18.jar"))

        // in transitive modules, optional dependencies are always ignored
        assert(!dependsOnOptionalCompileCp.exists(_.last == "interface-1.0.29-M1.jar"))
        assert(!dependsOnOptionalRuntimeCp.exists(_.last == "interface-1.0.29-M1.jar"))
        assert(!dependsOnOptionalRunCp.exists(_.last == "interface-1.0.29-M1.jar"))
        assert(!dependsOnOptionalCompileCp.exists(_.last == "sourcecode_3-0.4.3-M5.jar"))
        assert(!dependsOnOptionalRuntimeCp.exists(_.last == "sourcecode_3-0.4.3-M5.jar"))
        assert(!dependsOnOptionalRunCp.exists(_.last == "sourcecode_3-0.4.3-M5.jar"))
        assert(!dependsOnOptionalCompileCp.exists(_.last == "logback-core-1.5.18.jar"))
        assert(!dependsOnOptionalRuntimeCp.exists(_.last == "logback-core-1.5.18.jar"))
        assert(!dependsOnOptionalRunCp.exists(_.last == "logback-core-1.5.18.jar"))
      }
    }
  }
}
