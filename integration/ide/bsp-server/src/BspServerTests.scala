package mill.integration

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*
import scala.util.chaining.given

import ch.epfl.scala.bsp4j as b
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.constants.OutFiles
import mill.integration.BspServerTestUtil.*
import mill.testkit.UtestIntegrationTestSuite
import mill.testrunner.TestRunnerUtils
import utest.*

object BspServerTests extends UtestIntegrationTestSuite {
  protected def snapshotsPath: os.Path =
    super.workspaceSourcePath / "snapshots"
  def logsPath: os.Path =
    super.workspaceSourcePath / "logs"
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
      import tester.*
      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
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

        val appTargetId = new b.BuildTargetIdentifier(
          (workspacePath / "app").toNIO.toUri.toASCIIString.stripSuffix("/")
        )
        assert(targetIds.contains(appTargetId))

        compareWithGsonSnapshot(
          buildServer
            .buildTargetSources(new b.SourcesParams(targetIds))
            .get(),
          snapshotsPath / "build-targets-sources.json",
          normalizedLocalValues = normalizedLocalValues
        )

        {
          val file = workspacePath / "app/src/App.java"
          assert(initRes.getCapabilities.getInverseSourcesProvider == true)
          assert(os.exists(file))

          compareWithGsonSnapshot(
            buildServer
              .buildTargetInverseSources(
                new b.InverseSourcesParams(
                  new b.TextDocumentIdentifier(file.toNIO.toUri().toASCIIString)
                )
              )
              .get(),
            snapshotsPath / "build-targets-inverse-sources.json",
            normalizedLocalValues = normalizedLocalValues
          )
        }

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

        // compile
        compareWithGsonSnapshot(
          buildServer
            .buildTargetCompile(
              new b.CompileParams(
                targetIds
                  .asScala
                  .filter(!_.getUri.endsWith("/errored/exception"))
                  .filter(!_.getUri.endsWith("/errored/compilation-error"))
                  .filter(!_.getUri.endsWith("/delayed"))
                  .asJava
              )
            )
            .get(),
          snapshotsPath / "build-targets-compile.json",
          normalizedLocalValues = normalizedLocalValues
        )

        // Jvm Extension

        compareWithGsonSnapshot(
          buildServer
            .buildTargetJvmRunEnvironment(new b.JvmRunEnvironmentParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-jvm-run-environments.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          cleanUpJvmTestEnvResult(
            buildServer
              .buildTargetJvmTestEnvironment(new b.JvmTestEnvironmentParams(targetIdsSubset))
              .get()
          ),
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

        // Java Extention

        compareWithGsonSnapshot(
          buildServer
            .buildTargetJavacOptions(new b.JavacOptionsParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-javac-options.json",
          normalizedLocalValues = normalizedLocalValues
        )

        // Scala Extension

        compareWithGsonSnapshot(
          buildServer
            .buildTargetScalacOptions(new b.ScalacOptionsParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-scalac-options.json",
          normalizedLocalValues = normalizedLocalValues
        )

        compareWithGsonSnapshot(
          buildServer
            .buildTargetScalaMainClasses(new b.ScalaMainClassesParams(targetIdsSubset))
            .get(),
          snapshotsPath / "build-targets-scalac-main-classes.json",
          normalizedLocalValues = normalizedLocalValues
        )

        // Run without args
        compareWithGsonSnapshot(
          buildServer.buildTargetRun(new b.RunParams(appTargetId)).get(),
          snapshotsPath / "build-targets-run-1.json",
          normalizedLocalValues = normalizedLocalValues
        )

        {
          val run3 = os.temp(suffix = "bsp-run-3", deleteOnExit = false)
          println("file: " + run3)
          os.remove(run3)
          compareWithGsonSnapshot(
            buildServer
              .buildTargetRun(new b.RunParams(appTargetId).tap { p =>
                p.setArguments(java.util.List.of(s"file=${run3.toString}", "content=run-3"))
              })
              .get(),
            snapshotsPath / "build-targets-run-1.json",
            normalizedLocalValues = normalizedLocalValues
          )
          assert(os.exists(run3))
          assert(os.read(run3).trim() == "run-3")
        }

        val scalacOptionsResult = buildServer
          .buildTargetScalacOptions(new b.ScalacOptionsParams(targetIds))
          .get()

        val expectedScalaSemDbs = Map(
          os.sub / "hello-scala" -> Seq(
            os.sub / "hello-scala/src/Hello.scala.semanticdb"
          ),
          os.sub / "hello-scala/test" -> Seq(
            os.sub / "hello-scala/test/src/HelloTest.scala.semanticdb"
          ),
          os.sub / "mill-build" -> Nil,
          os.sub / "errored/exception" -> Nil,
          os.sub / "errored/compilation-error" -> Nil,
          os.sub / "delayed" -> Nil
        )

        {
          // check that semanticdbs are generated for Scala modules
          val semDbs = scalacOptionsResult
            .getItems
            .asScala
            .map { item =>
              val shortId = os.Path(Paths.get(new URI(item.getTarget.getUri)))
                .relativeTo(workspacePath)
                .asSubPath
              val semDbs = findSemanticdbs(
                os.Path(Paths.get(new URI(item.getClassDirectory)))
              )
              shortId -> semDbs
            }
            .toMap
          if (expectedScalaSemDbs != semDbs) {
            pprint.err.log(expectedScalaSemDbs)
            pprint.err.log(semDbs)
          }
          assert(expectedScalaSemDbs == semDbs)
        }

        {
          // check that semanticdbs are generated for Java modules
          val javacOptionsResult = buildServer
            .buildTargetJavacOptions(new b.JavacOptionsParams(targetIds))
            .get()
          val semDbs = javacOptionsResult
            .getItems
            .asScala
            .map { item =>
              val shortId = os.Path(Paths.get(new URI(item.getTarget.getUri)))
                .relativeTo(workspacePath)
                .asSubPath
              val semDbs = findSemanticdbs(
                os.Path(Paths.get(new URI(item.getClassDirectory)))
              )
              shortId -> semDbs
            }
            .toMap
          val expectedJavaSemDbs = expectedScalaSemDbs ++ Seq(
            os.sub / "app" -> Seq(
              os.sub / "app/src/App.java.semanticdb"
            ),
            os.sub / "app/test" -> Nil,
            os.sub / "hello-kotlin" -> Nil,
            os.sub / "lib" -> Nil,
            os.sub / "hello-java" -> Nil,
            os.sub / "hello-java/test" -> Seq(
              os.sub / "hello-java/test/src/HelloJavaTest.java.semanticdb"
            )
          )
          if (expectedJavaSemDbs != semDbs) {
            pprint.err.log(expectedJavaSemDbs)
            pprint.err.log(semDbs)
          }
          assert(expectedJavaSemDbs == semDbs)
        }
      }
    }

    test("logging") - integrationTest { tester =>
      import tester.*
      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      var messages = Seq.empty[b.ShowMessageParams]
      val client: b.BuildClient = new DummyBuildClient {
        override def onBuildShowMessage(params: b.ShowMessageParams): Unit = {
          messages = messages :+ params
        }
      }

      val stderr = new ByteArrayOutputStream
      withBspServer(
        workspacePath,
        millTestSuiteEnv,
        bspLog = Some((bytes, len) => stderr.write(bytes, 0, len)),
        client = client
      ) { (buildServer, _) =>
        val targets = buildServer.workspaceBuildTargets().get().getTargets.asScala
        buildServer.loggingTest().get()

        buildServer.buildTargetCompile(
          new b.CompileParams(
            targets.filter(_.getDisplayName == "errored.exception").map(_.getId).asJava
          )
        ).get()

        buildServer.buildTargetCompile(
          new b.CompileParams(
            targets.filter(_.getDisplayName == "errored.compilation-error").map(_.getId).asJava
          )
        ).get()

        // Submitting two compilation requests in a row, and cancelling
        // the second one.
        // We shouldn't get any log from the cancelled one, apart from
        // a "… was cancelled" message.
        val delayedCompileFuture = buildServer.buildTargetCompile(
          new b.CompileParams(
            targets.filter(_.getDisplayName == "delayed").map(_.getId).asJava
          )
        )
        val erroredCompileFuture = buildServer.buildTargetCompile(
          new b.CompileParams(
            targets.filter(_.getDisplayName == "errored.exception").map(_.getId).asJava
          )
        )
        erroredCompileFuture.cancel(true)
        delayedCompileFuture.get()
      }

      val logs = stderr.toString
        .linesWithSeparators
        .filter(_.startsWith("["))
        .mkString

      val expectedCancelledLine = "[7-compile] buildTargetCompile was cancelled"

      assert(logs.linesIterator.contains(expectedCancelledLine))

      compareLogWithSnapshot(
        logs,
        snapshotsPath / "logging",
        // ignoring compilation warnings that might go away in the future
        ignoreLine = {
          val warnGlob = TestRunnerUtils.matchesGlob("[bsp-init-build.mill-*] [warn] *")
          val waitingGlob = TestRunnerUtils.matchesGlob("[*] Another Mill process is running *")
          s =>
            warnGlob(s) || waitingGlob(s) ||
              // Ignoring this one, that sometimes comes out of order.
              // If the request hasn't been cancelled, we'd see extra lines making the
              // test fail anyway.
              s == expectedCancelledLine
        }
      )

      val messages0 = messages.map { message =>
        (message.getType, message.getMessage)
      }
      val expectedMessages = Seq(
        // no message for errored.compilation-error, compilation diagnostics are enough
        (b.MessageType.ERROR, "Compiling errored.exception failed, see Mill logs for more details")
      )
      assert(expectedMessages == messages0)
    }
  }

  private def cleanUpJvmTestEnvResult(res: b.JvmTestEnvironmentResult): res.type = {
    for {
      item <- res.getItems.asScala
      mc <- item.getMainClasses.asScala
      if mc.getArguments.size() > 0
    }
      // zero-out first arg that contains parts of the Mill class path, whose versions and libraries
      // should change quite often - no need to track those here
      mc.setArguments(
        ("" +: mc.getArguments.asScala.drop(1)).asJava
      )
    res
  }

  private def semDbPrefix = os.sub / "META-INF/semanticdb"
  private def findSemanticdbs(classDir: os.Path) =
    if (os.exists(classDir))
      os.walk(classDir)
        .filter(os.isFile)
        .map(_.relativeTo(classDir).asSubPath)
        .filter(_.last.endsWith(".semanticdb"))
        .filter(_.startsWith(semDbPrefix))
        .map(_.relativeTo(semDbPrefix).asSubPath)
        .filter(!_.startsWith(os.sub / OutFiles.out))
        .sorted
    else
      Nil
}
