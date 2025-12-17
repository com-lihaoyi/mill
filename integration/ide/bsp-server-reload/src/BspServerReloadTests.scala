package mill.integration

import ch.epfl.scala.{bsp4j => b}
import mill.api.BuildInfo
import mill.bsp.Constants
import mill.integration.BspServerTestUtil._
import mill.testkit.UtestIntegrationTestSuite
import utest._

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.Success

object BspServerReloadTests extends UtestIntegrationTestSuite {
  override protected def workspaceSourcePath: os.Path =
    super.workspaceSourcePath / "project"

  def tests = Tests {
    test("reload") - integrationTest { tester =>
      import tester._

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
        os.copy.over(
          workspacePath / "build.mill.deletion-and-renaming",
          workspacePath / "build.mill"
        )

        System.err.println(
          s"Waiting for Mill daemon to pick up changes in ${workspacePath / "build.mill"}"
        )
        val didChangeParams = Await.result(didChangePromise.future, 1.minute)

        def eventData(event: b.BuildTargetEvent): (String, b.BuildTargetEventKind) =
          (event.getTarget.getUri.split("/").last, event.getKind)

        val expectedChanges = Set(
          "thing" -> b.BuildTargetEventKind.DELETED,
          "app" -> b.BuildTargetEventKind.DELETED,
          "my-app" -> b.BuildTargetEventKind.CREATED,
          "lib" -> b.BuildTargetEventKind.CHANGED,
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
      import tester._

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

      var didChangePromise = Promise[b.DidChangeBuildTarget]()

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

        def eventData(event: b.BuildTargetEvent): (String, b.BuildTargetEventKind) =
          (event.getTarget.getUri.split("/").last, event.getKind)

        // Don't reset didChangePromise at this point. The incoming didChange notification
        // must be the first one since the session started. This ensures we don't send a
        // superfluous notification upfront when starting the BSP server (which can happen
        // if we're not careful).
        os.copy.over(workspacePath / "build.mill.base", workspacePath / "build.mill")

        System.err.println(
          s"Waiting for Mill daemon to pick up changes in ${workspacePath / "build.mill"}"
        )
        val didChangeParams = Await.result(didChangePromise.future, 1.minute)

        val expectedChanges = Set(
          "thing" -> b.BuildTargetEventKind.CREATED,
          "app" -> b.BuildTargetEventKind.CREATED,
          "lib" -> b.BuildTargetEventKind.CREATED,
          "Lib.scala" -> b.BuildTargetEventKind.DELETED,
          "TheApp.scala" -> b.BuildTargetEventKind.DELETED,
          "mill-build" -> b.BuildTargetEventKind.CHANGED,
          "MillMiscInfo.scala" -> b.BuildTargetEventKind.DELETED,
          "BuildFileImpl.scala" -> b.BuildTargetEventKind.DELETED
        )
        val changes = didChangeParams.getChanges().asScala.map(eventData).toSet
        assert(expectedChanges == changes)

        val afterChangesBuildTargets = buildServer.workspaceBuildTargets().get()
        compareWithGsonSnapshot(
          afterChangesBuildTargets,
          afterChangesSnapshotsPath / "workspace-build-targets.json",
          normalizedLocalValues = normalizedLocalValues
        )

        didChangePromise = Promise[b.DidChangeBuildTarget]()
        os.copy.over(workspacePath / "build.mill.broken", workspacePath / "build.mill")

        System.err.println(
          s"Waiting for Mill daemon to pick up changes in ${workspacePath / "build.mill"}"
        )
        val didChangeParams0 = Await.result(didChangePromise.future, 1.minute)

        val expectedChanges0 = Set(
          "mill-build" -> b.BuildTargetEventKind.CHANGED
        )
        val changes0 = didChangeParams0.getChanges().asScala.map(eventData).toSet
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
        val appCompileRes = buildServer
          .buildTargetCompile(new b.CompileParams(List(targetsMap("app")).asJava))
          .get()
        assert(
          buildCompileRes.getStatusCode == b.StatusCode.ERROR,
          appCompileRes.getStatusCode == b.StatusCode.OK
        )
      }
    }

    // TODO Watch meta-meta-build
    // TODO Start on broken meta-meta-build
    // TODO Do more than just calling workspaceBuildTargets on main build with broken meta-build
  }
}
