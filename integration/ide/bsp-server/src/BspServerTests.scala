package mill.integration

import ch.epfl.scala.{bsp4j => b}
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.integration.BspServerTestUtil._
import mill.testkit.UtestIntegrationTestSuite
import utest._

import scala.jdk.CollectionConverters._

object BspServerTests extends UtestIntegrationTestSuite {
  def snapshotsPath: os.Path =
    super.workspaceSourcePath / "snapshots"
  override protected def workspaceSourcePath: os.Path =
    super.workspaceSourcePath / "project"

  def transitiveDependenciesSubstitutions(
      dependency: coursierapi.Dependency,
      filter: coursierapi.Dependency => Boolean
  ): Seq[(String, String)] = {
    val fetchRes = coursierapi.Fetch.create()
      .addDependencies(dependency)
      .fetchResult()
    fetchRes.getDependencies.asScala
      .filter(filter)
      .map { dep =>
        val organization = dep.getModule.getOrganization
        val name = dep.getModule.getName
        val prefix = (organization.split('.') :+ name).mkString("/")
        def basePath(version: String): String =
          s"$prefix/$version/$name-$version"
        basePath(dep.getVersion) -> basePath(s"<$name-version>")
      }
      .toSeq
  }

  def tests: Tests = Tests {
    test("requestSnapshots") - integrationTest { tester =>
      import tester._
      eval(
        "mill.bsp.BSP/install",
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_MAIN_CLI" -> tester.millExecutable.toString)
      )

      withBspServer(
        workspacePath,
        millTestSuiteEnv
      ) { (buildServer, initRes) =>
        val scala2Version = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
        val scala3Version = sys.props.getOrElse("MILL_SCALA_3_NEXT_VERSION", ???)
        val scala2TransitiveSubstitutions = transitiveDependenciesSubstitutions(
          coursierapi.Dependency.of(
            "org.scala-lang",
            "scala-compiler",
            scala2Version
          ),
          _.getModule.getOrganization != "org.scala-lang"
        )
        val scala3TransitiveSubstitutions = transitiveDependenciesSubstitutions(
          coursierapi.Dependency.of(
            "org.scala-lang",
            "scala3-compiler_3",
            scala3Version
          ),
          _.getModule.getOrganization != "org.scala-lang"
        )

        val kotlinVersion = sys.props.getOrElse("TEST_KOTLIN_VERSION", ???)
        val kotlinTransitiveSubstitutions = transitiveDependenciesSubstitutions(
          coursierapi.Dependency.of(
            "org.jetbrains.kotlin",
            "kotlin-stdlib",
            kotlinVersion
          ),
          _.getModule.getOrganization != "org.jetbrains.kotlin"
        )

        val normalizedLocalValues = normalizeLocalValuesForTesting(workspacePath) ++
          scala2TransitiveSubstitutions ++
          scala3TransitiveSubstitutions ++
          kotlinTransitiveSubstitutions ++
          Seq(
            scala2Version -> "<scala-version>",
            scala3Version -> "<scala3-version>",
            kotlinVersion -> "<kotlin-version>"
          )

        compareWithGsonSnapshot(
          initRes,
          snapshotsPath / "initialize-build-result.json",
          normalizedLocalValues = Seq(
            BuildInfo.millVersion -> "<MILL_VERSION>",
            Constants.bspProtocolVersion -> "<BSP_VERSION>"
          )
        )

        val buildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          buildTargets,
          snapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        val targetIds = buildTargets.getTargets.asScala.map(_.getId).asJava
        val metaBuildTargetId = new b.BuildTargetIdentifier(
          (workspacePath / "mill-build").toNIO.toUri.toASCIIString.stripSuffix("/")
        )
        assert(targetIds.contains(metaBuildTargetId))
        val targetIdsSubset = targetIds.asScala.filter(_ != metaBuildTargetId).asJava

        compareWithGsonSnapshot(
          buildServer
            .buildTargetSources(new b.SourcesParams(targetIds))
            .get(),
          snapshotsPath / "build-targets-sources.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetDependencySources(new b.DependencySourcesParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-dependency-sources.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetDependencyModules(new b.DependencyModulesParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-dependency-modules.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetResources(new b.ResourcesParams(targetIds))
            .get(),
          snapshotsPath / "build-targets-resources.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetOutputPaths(new b.OutputPathsParams(targetIds))
            .get(),
          snapshotsPath / "build-targets-output-paths.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetJvmRunEnvironment(new b.JvmRunEnvironmentParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-jvm-run-environments.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetJvmTestEnvironment(new b.JvmTestEnvironmentParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-jvm-test-environments.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetJvmCompileClasspath(new b.JvmCompileClasspathParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-compile-classpaths.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetJavacOptions(new b.JavacOptionsParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-javac-options.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetScalacOptions(new b.ScalacOptionsParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-scalac-options.json",
          normalizedLocalValues = normalizedLocalValues
        )
      }
    }
  }
}
