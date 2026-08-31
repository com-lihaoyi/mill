package mill.integration

import ch.epfl.scala.{bsp4j => b}
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.integration.BspServerTestUtil.*
import mill.testkit.UtestIntegrationTestSuite
import utest.*

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Success

object BspServerReloadTests extends UtestIntegrationTestSuite {
  override protected def workspaceSourcePath: os.Path =
    super.workspaceSourcePath / "project"

  private def replaceFile(from: os.Path, to: os.Path): Unit = {
    val staged = to / os.up / s".${to.last}.replacement"
    os.copy.over(from, staged)
    os.move.over(staged, to)
  }

  def tests = Tests {
    test("reload") - integrationTest { tester =>
      import tester.*

      val startSnapshotsPath = super.workspaceSourcePath / "snapshots" / "reload" / "start"
      val afterChangesSnapshotsPath = super.workspaceSourcePath / "snapshots" / "reload" / "changed"

      os.copy.over(workspacePath / "build.mill.base", workspacePath / "build.mill")

      eval(
        "mill.bsp.BSP/install",
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      val didChangePromise = Promise[b.DidChangeBuildTarget]()

      val client = new DummyBuildClient {
        override def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit =
          didChangePromise.complete(Success(params))
      }

      withBspServer(
        workspacePath,
        millTestSuiteEnv,
        client = client
      ) { (buildServer, initRes) =>

        compareWithGsonSnapshot(
          initRes,
          startSnapshotsPath / "initialize-build-result.json",
          normalizedLocalValues = Seq(
            BuildInfo.millVersion -> "<MILL_VERSION>",
            Constants.bspProtocolVersion -> "<BSP_VERSION>"
          )
        )

        val normalizedLocalValues =
          normalizeLocalValuesForTesting(workspacePath) ++ scalaVersionNormalizedValues()

        val buildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          buildTargets,
          startSnapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        // Overwrite build.mill without changing its content - shouldn't trigger
        // a didChange notification (checked below)
        os.write.over(workspacePath / "build.mill", os.read.bytes(workspacePath / "build.mill"))

        // Running a new request to be sure the overwritten build.mill is taken into account
        compareWithGsonSnapshot(
          buildServer.workspaceBuildTargets().get(),
          startSnapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        // Don't reset didChangePromise at this point. The incoming didChange notification
        // must be the first one since the session started. This ensures we don't send a
        // superfluous notification upfront when starting the BSP server (which can happen
        // if we're not careful).
        replaceFile(
          workspacePath / "build.mill.deletion-and-renaming",
          workspacePath / "build.mill"
        )

        System.err.println(
          s"Waiting for Mill daemon to pick up changes in ${workspacePath / "build.mill"}"
        )
        val didChangeParams = Await.result(didChangePromise.future, 1.minute)

        def eventData(event: b.BuildTargetEvent): (String, b.BuildTargetEventKind) =
          (event.getTarget.getUri.split("/").last, event.getKind)

        // Existing targets are conservatively changed because any build definition edit can
        // affect their BSP model through shared helpers or inherited configuration.
        val expectedChanges = Set(
          "lib" -> b.BuildTargetEventKind.CHANGED,
          "thing" -> b.BuildTargetEventKind.DELETED,
          "app" -> b.BuildTargetEventKind.DELETED,
          "my-app" -> b.BuildTargetEventKind.CREATED,
          "mill-build" -> b.BuildTargetEventKind.CHANGED
        )
        val changes = didChangeParams.getChanges().asScala.map(eventData).toSet
        assert(expectedChanges == changes)

        val afterChangesBuildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          afterChangesBuildTargets,
          afterChangesSnapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )
      }
    }

    test("broken") - integrationTest { tester =>
      import tester.*

      val startSnapshotsPath = super.workspaceSourcePath / "snapshots" / "broken" / "start"
      val afterChangesSnapshotsPath = super.workspaceSourcePath / "snapshots" / "broken" / "changed"
      val afterChangesSnapshotsPath0 = super.workspaceSourcePath / "snapshots" / "broken" / "back"

      os.copy.over(workspacePath / "build.mill.broken", workspacePath / "build.mill")

      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      val didChanges = new LinkedBlockingQueue[b.DidChangeBuildTarget]()

      val client = new DummyBuildClient {
        override def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit =
          didChanges.add(params)
      }

      withBspServer(
        workspacePath,
        millTestSuiteEnv,
        client = client
      ) { (buildServer, initRes) =>

        compareWithGsonSnapshot(
          initRes,
          startSnapshotsPath / "initialize-build-result.json",
          normalizedLocalValues = Seq(
            BuildInfo.millVersion -> "<MILL_VERSION>",
            Constants.bspProtocolVersion -> "<BSP_VERSION>"
          )
        )

        val normalizedLocalValues =
          normalizeLocalValuesForTesting(workspacePath) ++ scalaVersionNormalizedValues()

        val buildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          buildTargets,
          startSnapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        def eventData(event: b.BuildTargetEvent): (String, b.BuildTargetEventKind) =
          (event.getTarget.getUri.split("/").last, event.getKind)

        def awaitChanges(expected: Set[(String, b.BuildTargetEventKind)]) = {
          val deadline = System.nanoTime() + 1.minute.toNanos
          var changes = Set.empty[(String, b.BuildTargetEventKind)]
          while (!expected.subsetOf(changes) && System.nanoTime() < deadline) {
            val params = didChanges.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            assert(params != null)
            changes = params.getChanges.asScala.map(eventData).toSet
          }
          changes
        }

        // A recovering build can report an intermediate mill-build change before the
        // successfully loaded user targets. Wait for the coherent terminal event batch.
        replaceFile(workspacePath / "build.mill.base", workspacePath / "build.mill")

        System.err.println(
          s"Waiting for Mill daemon to pick up changes in ${workspacePath / "build.mill"}"
        )
        import b.BuildTargetEventKind.*
        val expectedChanges =
          Set(("app", CREATED), ("lib", CREATED), ("thing", CREATED), ("mill-build", CHANGED))
        val changes = awaitChanges(expectedChanges)
        assert(expectedChanges == changes)

        val afterChangesBuildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          afterChangesBuildTargets,
          afterChangesSnapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        didChanges.clear()
        replaceFile(workspacePath / "build.mill.broken", workspacePath / "build.mill")

        System.err.println(
          s"Waiting for Mill daemon to pick up changes in ${workspacePath / "build.mill"}"
        )
        val expectedChanges0 = Set(
          "app" -> b.BuildTargetEventKind.DELETED,
          "lib" -> b.BuildTargetEventKind.DELETED,
          "thing" -> b.BuildTargetEventKind.DELETED,
          "mill-build" -> b.BuildTargetEventKind.CHANGED
        )
        val changes0 = awaitChanges(expectedChanges0)
        assert(expectedChanges0 == changes0)

        val afterChangesBuildTargets0 = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          afterChangesBuildTargets0,
          afterChangesSnapshotsPath0 / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        val targetsMap = afterChangesBuildTargets0.getTargets.asScala
          .map(target => target.getId.getUri.split("/").last -> target.getId)
          .toMap

        val buildCompileRes = buildServer
          .buildTargetCompile(new b.CompileParams(List(targetsMap("mill-build")).asJava))
          .get()
        assert(
          buildCompileRes.getStatusCode == b.StatusCode.ERROR,
          !targetsMap.contains("app")
        )
      }
    }

    test("configurationChanges") - integrationTest { tester =>
      import tester.*

      os.remove(workspacePath / "build.mill")
      os.copy.over(
        workspacePath / "build.mill.yaml.configuration-base",
        workspacePath / "build.mill.yaml"
      )

      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      val didChanges = new LinkedBlockingQueue[b.DidChangeBuildTarget]()
      val client = new DummyBuildClient {
        override def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit =
          didChanges.add(params)
      }

      withBspServer(workspacePath, millTestSuiteEnv, client = client) {
        (buildServer, _) =>
          val initialTargets = buildServer.workspaceBuildTargets().get()
          val targetId = initialTargets.getTargets.asScala
            .find(_.getDisplayName == "root-module")
            .get
            .getId

          def compileClasspath = buildServer
            .buildTargetJvmCompileClasspath(
              new b.JvmCompileClasspathParams(List(targetId).asJava)
            )
            .get(1, TimeUnit.MINUTES)
            .getItems
            .get(0)
            .getClasspath
            .asScala
            .toSeq

          val initialClasspath = compileClasspath
          assert(
            initialClasspath.exists(_.contains("slf4j-api-2.0.16.jar")),
            !initialClasspath.exists(_.contains("slf4j-api-2.0.17.jar"))
          )
          def javaHome(targets: b.WorkspaceBuildTargetsResult) =
            ujson.read(gson.toJson(
              targets.getTargets.asScala.find(_.getId == targetId).get
            ))("data")("javaHome").str

          val initialJavaHome = javaHome(initialTargets)

          def assertNoTargetChange(): Unit =
            assert(didChanges.poll(2, TimeUnit.SECONDS) == null)

          def awaitTargetChange(): Unit = {
            val deadline = System.nanoTime() + 1.minute.toNanos
            var changed = false
            while (!changed && System.nanoTime() < deadline) {
              val params = didChanges.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
              assert(params != null)
              assert(params.getChanges.asScala.forall(
                _.getKind == b.BuildTargetEventKind.CHANGED
              ))
              changed = params.getChanges.asScala.exists { event =>
                event.getTarget == targetId && event.getKind == b.BuildTargetEventKind.CHANGED
              }
            }
            assert(changed)
          }

          // Building the first target snapshot initializes modules and discovers more
          // BuildCtx watches. The watcher's stabilization bootstrap must absorb those
          // unchanged watches without reporting a configuration change.
          assertNoTargetChange()

          replaceFile(
            workspacePath / "build.mill.yaml.configuration-dependency-changed",
            workspacePath / "build.mill.yaml"
          )

          awaitTargetChange()

          val changedClasspath = compileClasspath
          assert(
            changedClasspath.exists(_.contains("slf4j-api-2.0.17.jar")),
            !changedClasspath.exists(_.contains("slf4j-api-2.0.16.jar"))
          )
          assertNoTargetChange()

          replaceFile(
            workspacePath / "build.mill.yaml.configuration-jvm-changed",
            workspacePath / "build.mill.yaml"
          )

          awaitTargetChange()

          val changedTargets = buildServer.workspaceBuildTargets().get(1, TimeUnit.MINUTES)
          assert(initialJavaHome != javaHome(changedTargets))
          assertNoTargetChange()
      }
    }

    // TODO Watch meta-meta-build
    // TODO Start on broken meta-meta-build
    // TODO Do more than just calling workspaceBuildTargets on main build with broken meta-build
  }
}
