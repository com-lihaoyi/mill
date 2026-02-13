package mill.integration

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Paths
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.chaining.given
import ch.epfl.scala.bsp4j as b
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.constants.OutFolderMode
import mill.constants.OutFiles.OutFiles
import mill.integration.BspServerTestUtil.*
import mill.javalib.testrunner.TestRunnerUtils
import mill.testkit.UtestIntegrationTestSuite
import utest.*
// make generated golden lierals happy
import scala.collection.immutable.Seq as ArraySeq

object BspServerTests extends UtestIntegrationTestSuite {
  protected def snapshotsPath: os.Path =
    super.workspaceSourcePath / "snapshots"
  override protected def workspaceSourcePath: os.Path =
    super.workspaceSourcePath / "project"

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
        val kotlinVersion = sys.props.getOrElse("TEST_KOTLIN_VERSION", ???)
        transitiveDependenciesSubstitutions(
          coursierapi.Dependency.of(
            "org.jetbrains.kotlin",
            "kotlin-stdlib",
            kotlinVersion
          ),
          _.getModule.getOrganization != "org.jetbrains.kotlin"
        )

        val normalizedLocalValues = normalizeLocalValuesForTesting(workspacePath) ++
          scalaVersionNormalizedValues() ++
          kotlinVersionNormalizedValues()

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
          (workspacePath / "mill-build").toURI.toASCIIString.stripSuffix("/")
        )
        val metaBuildBuildTargetId = new b.BuildTargetIdentifier(
          (workspacePath / "mill-build/mill-build").toNIO.toUri.toASCIIString.stripSuffix("/")
        )
        assert(targetIds.contains(metaBuildTargetId))
        assert(targetIds.contains(metaBuildBuildTargetId))
        val targetIdsSubset = targetIds.asScala
          .filter(_ != metaBuildTargetId)
          .filter(_ != metaBuildBuildTargetId)
          .asJava

        val appTargetId = new b.BuildTargetIdentifier(
          (workspacePath / "app").toURI.toASCIIString.stripSuffix("/")
        )
        assert(targetIds.contains(appTargetId))

        def inverseSource(src: os.SubPath): Seq[b.BuildTargetIdentifier] =
          buildServer
            .buildTargetInverseSources(
              new b.InverseSourcesParams(
                new b.TextDocumentIdentifier(
                  (workspacePath / src).toNIO.toUri.toASCIIString
                )
              )
            )
            .get()
            .getTargets
            .asScala
            .toSeq

        val helloScalaTargetId = new b.BuildTargetIdentifier(
          (workspacePath / "hello-scala").toURI.toASCIIString.stripSuffix("/")
        )
        val foundHelloScalaTargetIds = inverseSource(os.sub / "hello-scala/src/Hello.scala")
        assert(foundHelloScalaTargetIds == Seq(helloScalaTargetId))

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
                  new b.TextDocumentIdentifier(file.toURI.toASCIIString)
                )
              )
              .get(),
            snapshotsPath / "build-targets-inverse-sources.json",
            normalizedLocalValues = normalizedLocalValues
          )

          // check that inverseSources works fine for the build.mill files
          val buildMillTargetIds = inverseSource(os.sub / "build.mill")
          assert(buildMillTargetIds == Seq(metaBuildTargetId))
          val buildBuildMillTargetIds = inverseSource(os.sub / "mill-build/build.mill")
          assert(buildBuildMillTargetIds == Seq(metaBuildBuildTargetId))
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
                  // No need to attempt to compile the failing targets.
                  // The snapshot data for this request basically only contains
                  // a global status, errored or success. By excluding these,
                  // we get a success status, and ensure compilation succeeds
                  // as expected for all other targets.
                  .filter(!_.getUri.endsWith("/errored/exception"))
                  .filter(!_.getUri.endsWith("/errored/compilation-error"))
                  .filter(!_.getUri.endsWith("/delayed"))
                  .filter(!_.getUri.endsWith("/diag/many"))
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
            .buildTargetScalaMainClasses(new b.ScalaMainClassesParams(targetIds))
            .get(),
          snapshotsPath / "build-targets-scala-main-classes.json",
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

          assertGoldenLiteral(
            semDbs.map { case (k, vs) => (k.toString, vs.map(_.toString)) },
            Map(
              "diag/many" -> List(),
              "mill-build" -> Seq("build.mill.semanticdb"),
              "hello-scala/test" -> Seq("hello-scala/test/src/HelloTest.scala.semanticdb"),
              "scripts/folder1/script.scala" -> Seq(),
              "errored/exception" -> List(),
              "hello-scala" -> Seq("hello-scala/src/Hello.scala.semanticdb"),
              "diag" -> Seq("diag/src/DiagCheck.scala.semanticdb"),
              "delayed" -> List(),
              "mill-build/mill-build" -> Seq("mill-build/build.mill.semanticdb"),
              "errored/compilation-error" -> List(),
              "scripts/foldershared/script.scala" -> Seq(),
              "sourcesNeedCompile" -> Seq()
            )
          )
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

          assertGoldenLiteral(
            semDbs.map { case (k, vs) => (k.toString, vs.map(_.toString)) },
            Map(
              "scripts/folder2/FooTest.java" -> ArraySeq("scripts/folder2/FooTest.java.semanticdb"),
              "mill-build" -> ArraySeq("build.mill.semanticdb"),
              "hello-kotlin" -> ArraySeq(),
              "hello-java" -> ArraySeq(),
              "hello-java/test" -> ArraySeq("hello-java/test/src/HelloJavaTest.java.semanticdb"),
              "app" -> ArraySeq("app/src/App.java.semanticdb"),
              "hello-scala/test" -> ArraySeq("hello-scala/test/src/HelloTest.scala.semanticdb"),
              "scripts/folder1/script.scala" -> ArraySeq(),
              "errored/exception" -> List(),
              "scripts/ignored-folder-2/negated-not-ignored.java" -> ArraySeq(),
              "app/test" -> ArraySeq(),
              "hello-scala" -> ArraySeq("hello-scala/src/Hello.scala.semanticdb"),
              "scripts/folder2/Foo.java" -> ArraySeq("scripts/folder2/Foo.java.semanticdb"),
              "diag/many" -> List(),
              "diag" -> ArraySeq("diag/src/DiagCheck.scala.semanticdb"),
              "delayed" -> List(),
              "lib" -> ArraySeq(),
              "scripts/foldershared/Foo.java" -> ArraySeq(
                "scripts/foldershared/Foo.java.semanticdb"
              ),
              "errored/compilation-error" -> List(),
              "scripts/foldershared/script.scala" -> ArraySeq(),
              "sourcesNeedCompile" -> ArraySeq(),
              "scripts" -> ArraySeq(),
              "mill-build/mill-build" -> ArraySeq("mill-build/build.mill.semanticdb")
            )
          )
        }
      }

      // Verify that no `out/` folder was created - all BSP operations should use .bsp/out/
      assert(!os.exists(workspacePath / "out"))
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
      val client: TestBuildClient = new DummyBuildClient {
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

      val workspaceUri = tester.workspacePath.toURI.toASCIIString.stripSuffix("/") + "/"
      val logs = stderr.toString
        .linesWithSeparators
        .filter { case s"$d] $_" if d.forall(_ != ' ') => true; case _ => false }
        .map(_.replace(workspaceUri, "file:///workspace/"))
        .mkString

      val expectedCancelledLine = "7-compile] buildTargetCompile was cancelled"

      assert(logs.linesIterator.contains(expectedCancelledLine))

      compareLogWithSnapshot(
        logs,
        snapshotsPath / "logging",
        ignoreLine = {
          // ignore watcher logs
          val watchGlob = TestRunnerUtils.matchesGlob("bsp-watch] *")
          // ignoring compilation warnings that might go away in the future
          val waitingGlob = TestRunnerUtils.matchesGlob("*] Another Mill process is running *")
          s =>
            watchGlob(s) || waitingGlob(s) ||
              // These can happen in different orders due to filesystem ordering, not stable to
              // assert against
              s.contains("Skipping script discovery") ||
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
      )
      assert(expectedMessages == messages0)
    }

    test("diagnostics") - integrationTest { tester =>
      import tester.*
      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      def uriAsSubPath(strUri: String): os.SubPath =
        os.Path(Paths.get(new URI(strUri))).relativeTo(workspacePath).asSubPath

      val normalizedLocalValues = normalizeLocalValuesForTesting(workspacePath) ++
        scalaVersionNormalizedValues()

      def runTest(): Unit = {
        var messages = Seq.empty[b.ShowMessageParams]
        val diagnostics = new mutable.ListBuffer[b.PublishDiagnosticsParams]
        val client: TestBuildClient = new DummyBuildClient {
          override def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = {
            // Not looking at diagnostics for generated sources of the build
            val keep =
              !uriAsSubPath(
                params.getTextDocument.getUri
              ).startsWith(os.sub / os.RelPath(OutFiles.outFor(OutFolderMode.BSP)))
            if (keep)
              diagnostics.append(params)
          }
          override def onBuildShowMessage(params: b.ShowMessageParams): Unit = {
            messages = messages :+ params
          }
        }

        withBspServer(
          workspacePath,
          millTestSuiteEnv,
          client = client
        ) { (buildServer, _) =>
          val targets = buildServer.workspaceBuildTargets().get().getTargets.asScala
          val diagTargets = targets.filter(_.getDisplayName == "diag").map(_.getId).asJava
          val diagManyTargets = targets.filter(_.getDisplayName == "diag.many").map(_.getId).asJava
          assert(!diagTargets.isEmpty())
          assert(!diagManyTargets.isEmpty())

          buildServer
            .buildTargetCompile(new b.CompileParams(diagTargets))
            .get()
          buildServer
            .buildTargetCompile(new b.CompileParams(diagManyTargets))
            .get()

          compareWithGsonSnapshot(
            diagnostics.asJava,
            snapshotsPath / "diagnostics.json",
            normalizedLocalValues = normalizedLocalValues
          )
        }
      }

      runTest()

      // drop "package build" from build.mill, the test should still pass,
      // diagnostic positions should all be the same
      val originalBuildMill = os.read(workspacePath / "build.mill")
      val idx = originalBuildMill.indexOf("package build")
      assert(idx >= 0)
      val noPackageBuildMill =
        originalBuildMill.take(idx) + originalBuildMill.drop(idx + "package build".length)
      os.write.over(workspacePath / "build.mill", noPackageBuildMill)
      os.remove.all(workspacePath / os.RelPath(OutFiles.outFor(OutFolderMode.BSP)))
      runTest()
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
        .filter(!_.startsWith(os.sub / os.RelPath(OutFiles.outFor(OutFolderMode.BSP))))
        .sorted
    else
      Nil
}
