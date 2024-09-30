package mill.integration

import ch.epfl.scala.{bsp4j => b}
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.testkit.BspServerUtil._
import mill.testkit.UtestIntegrationTestSuite
import utest._

import scala.jdk.CollectionConverters._

object BspServerTests extends UtestIntegrationTestSuite {
  def fixturePath: os.Path =
    super.workspaceSourcePath / "fixtures"
  override protected def workspaceSourcePath: os.Path =
    super.workspaceSourcePath / "project"

  def tests: Tests = Tests {
    test("BSP install") - integrationTest { tester =>
      import tester._
      eval("mill.bsp.BSP/install", stdout = os.Inherit, stderr = os.Inherit, check = true)

      withBspServer(
        workspacePath,
        millTestSuiteEnv
      ) { (buildServer, initRes) =>
        val scalaVersion = sys.props.getOrElse("MILL_TEST_SCALA_2_13_VERSION", ???)
        val scalaTransitiveSubstitutions = {
          val scalaFetchRes = coursierapi.Fetch.create()
            .addDependencies(coursierapi.Dependency.of(
              "org.scala-lang",
              "scala-compiler",
              scalaVersion
            ))
            .fetchResult()
          scalaFetchRes.getDependencies.asScala
            .filter(dep => dep.getModule.getOrganization != "org.scala-lang")
            .map { dep =>
              def basePath(version: String) =
                s"${dep.getModule.getOrganization.split('.').mkString("/")}/${dep.getModule.getName}/$version/${dep.getModule.getName}-$version"
              basePath(dep.getVersion) -> basePath(s"<${dep.getModule.getName}-version>")
            }
        }

        val replaceAll = replaceAllValues(workspacePath) ++ scalaTransitiveSubstitutions ++ Seq(
          scalaVersion -> "<scala-version>"
        )

        compareWithGsonFixture(
          initRes,
          fixturePath / "initialize-build-result.json",
          replaceAll = Seq(
            BuildInfo.millVersion -> "<MILL_VERSION>",
            Constants.bspProtocolVersion -> "<BSP_VERSION>"
          )
        )

        val buildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonFixture(
          buildTargets,
          fixturePath / "workspace-build-targets.json",
          replaceAll = replaceAll
        )

        val targetIds = buildTargets.getTargets.asScala.map(_.getId).asJava
        val metaBuildTargetId = new b.BuildTargetIdentifier(
          (workspacePath / "mill-build").toNIO.toUri.toASCIIString.stripSuffix("/")
        )
        assert(targetIds.contains(metaBuildTargetId))
        val targetIdsSubset = targetIds.asScala.filter(_ != metaBuildTargetId).asJava

        val buildTargetSources = buildServer
          .buildTargetSources(new b.SourcesParams(targetIds))
          .get()
        compareWithGsonFixture(
          buildTargetSources,
          fixturePath / "build-targets-sources.json",
          replaceAll = replaceAll
        )

        val buildTargetDependencySources = buildServer
          .buildTargetDependencySources(new b.DependencySourcesParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetDependencySources,
          fixturePath / "build-targets-dependency-sources.json",
          replaceAll = replaceAll
        )

        val buildTargetDependencyModules = buildServer
          .buildTargetDependencyModules(new b.DependencyModulesParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetDependencyModules,
          fixturePath / "build-targets-dependency-modules.json",
          replaceAll = replaceAll
        )

        val buildTargetResources = buildServer
          .buildTargetResources(new b.ResourcesParams(targetIds))
          .get()
        compareWithGsonFixture(
          buildTargetResources,
          fixturePath / "build-targets-resources.json",
          replaceAll = replaceAll
        )

        val buildTargetOutputPaths = buildServer
          .buildTargetOutputPaths(new b.OutputPathsParams(targetIds))
          .get()
        compareWithGsonFixture(
          buildTargetOutputPaths,
          fixturePath / "build-targets-output-paths.json",
          replaceAll = replaceAll
        )

        val buildTargetJvmRunEnvironments = buildServer
          .buildTargetJvmRunEnvironment(new b.JvmRunEnvironmentParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetJvmRunEnvironments,
          fixturePath / "build-targets-jvm-run-environments.json",
          replaceAll = replaceAll
        )

        val buildTargetJvmTestEnvironments = buildServer
          .buildTargetJvmTestEnvironment(new b.JvmTestEnvironmentParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetJvmTestEnvironments,
          fixturePath / "build-targets-jvm-test-environments.json",
          replaceAll = replaceAll
        )

        val buildTargetJvmCompileClasspaths = buildServer
          .buildTargetJvmCompileClasspath(new b.JvmCompileClasspathParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetJvmCompileClasspaths,
          fixturePath / "build-targets-compile-classpaths.json",
          replaceAll = replaceAll
        )

        val buildTargetJavacOptions = buildServer
          .buildTargetJavacOptions(new b.JavacOptionsParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetJavacOptions,
          fixturePath / "build-targets-javac-options.json",
          replaceAll = replaceAll
        )

        val buildTargetScalacOptions = buildServer
          .buildTargetScalacOptions(new b.ScalacOptionsParams(targetIdsSubset))
          .get()
        compareWithGsonFixture(
          buildTargetScalacOptions,
          fixturePath / "build-targets-scalac-options.json",
          replaceAll = replaceAll
        )
      }
    }
  }
}
