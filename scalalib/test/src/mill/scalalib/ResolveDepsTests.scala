package mill.scalalib

import coursier.maven.MavenRepository
import mill.api.Result.{Failure, Success}
import mill.api.{PathRef, Result}

import mill.define.{Discover, Task}
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import mill.main.TokenReaders._
object ResolveDepsTests extends TestSuite {
  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val repos =
    Seq(coursier.LocalRepositories.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))

  def evalDeps(deps: Seq[Dep]): Result[Seq[PathRef]] = Lib.resolveDependencies(
    repos,
    deps.map(Lib.depToBoundDep(_, scala212Version, "")),
    checkGradleModules = false
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
      assert(upickle.default.read[Dep](upickle.default.write(dep)) == dep)
    }
  }

  object TestCase extends TestBaseModule {
    object pomStuff extends JavaModule {
      def ivyDeps = Seq(
        // Dependency whose packaging is "pom", as it's meant to be used
        // as a "parent dependency" by other dependencies, rather than be pulled
        // as we do here. We do it anyway, to check that pulling the "pom" artifact
        // type brings that dependency POM file in the class path. We need a dependency
        // that has a "pom" packaging for that.
        ivy"org.apache.hadoop:hadoop-yarn-server:3.4.0"
      )
      def artifactTypes = super.artifactTypes() + coursier.Type("pom")
    }

    object scope extends JavaModule {
      def ivyDeps = Seq(
        ivy"androidx.compose.animation:animation-core:1.1.1",
        ivy"androidx.compose.ui:ui:1.1.1"
      )
      def repositoriesTask = Task.Anon {
        super.repositoriesTask() :+ coursier.Repositories.google
      }
      def artifactTypes = super.artifactTypes() + coursier.core.Type("aar")
    }

    lazy val millDiscover = Discover[this.type]
  }

  val tests = Tests {
    test("resolveValidDeps") {
      val deps = Seq(ivy"com.lihaoyi::pprint:0.5.3")
      val Success(paths) = evalDeps(deps): @unchecked
      assert(paths.nonEmpty)
    }

    test("resolveValidDepsWithClassifier") {
      val deps = Seq(ivy"org.lwjgl:lwjgl:3.1.1;classifier=natives-macos")
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(paths.nonEmpty)
      assert(paths.head.path.toString.contains("natives-macos"))
    }

    test("resolveTransitiveRuntimeDeps") {
      val deps = Seq(ivy"org.mockito:mockito-core:2.7.22")
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(paths.nonEmpty)
      assert(paths.exists(_.path.toString.contains("objenesis")))
      assert(paths.exists(_.path.toString.contains("byte-buddy")))
    }

    test("excludeTransitiveDeps") {
      val deps = Seq(ivy"com.lihaoyi::pprint:0.5.3".exclude("com.lihaoyi" -> "fansi_2.12"))
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    test("excludeTransitiveDepsByOrg") {
      val deps = Seq(ivy"com.lihaoyi::pprint:0.5.3".excludeOrg("com.lihaoyi"))
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(!paths.exists(path =>
        path.path.toString.contains("com/lihaoyi") && !path.path.toString.contains("pprint_2.12")
      ))
    }

    test("excludeTransitiveDepsByName") {
      val deps = Seq(ivy"com.lihaoyi::pprint:0.5.3".excludeName("fansi_2.12"))
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps): @unchecked
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    test("errOnInvalidOrgDeps") {
      val deps = Seq(ivy"xxx.yyy.invalid::pprint:0.5.3")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg) = evalDeps(deps): @unchecked
      assert(errMsg.contains("xxx.yyy.invalid"))
    }

    test("errOnInvalidVersionDeps") {
      val deps = Seq(ivy"com.lihaoyi::pprint:invalid.version.num")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg) = evalDeps(deps): @unchecked
      assert(errMsg.contains("invalid.version.num"))
    }

    test("errOnPartialSuccess") {
      val deps = Seq(ivy"com.lihaoyi::pprint:0.5.3", ivy"fake::fake:fake")
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
          .right.get.value.toSeq.map(_.path)
        val runtimeCp = eval(TestCase.scope.upstreamAssemblyClasspath)
          .right.get.value.toSeq.map(_.path)
        val runCp = eval(TestCase.scope.runClasspath)
          .right.get.value.toSeq.map(_.path)

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
  }
}
