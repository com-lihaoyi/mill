package mill.api

import mill.api.internal.PathAliasing
import mill.constants.EnvVars
import utest.*

object PathAliasingTests extends TestSuite {
  def tests: Tests = Tests {
    test("subprocessContextUsesAliasesOutsideDest") {
      val workspace = os.temp.dir()
      val out = workspace / "out"
      val dest = out / "foo.dest"
      val cwd = dest / "sub" / "dir"
      val context = PathAliasing.subprocessPathContext(cwd, workspace, outPath = Some(out)).get

      assert(
        context.taskDest == dest,
        context.aliasParent == out,
        context.workspaceAliasPath.toString == s"..${java.io.File.separator}..${java.io.File.separator}..${java.io.File.separator}mill-workspace",
        context.pathRelativizerBase(workspace).contains(
          s"..${java.io.File.separator}..${java.io.File.separator}..${java.io.File.separator}mill-workspace"
        )
      )
    }

    test("subprocessContextOnlyAppliesInsideTaskDest") {
      val workspace = os.temp.dir()
      val out = workspace / "out"
      val cwd = workspace / "src"

      assert(PathAliasing.subprocessPathContext(cwd, workspace, outPath = Some(out)).isEmpty)
      assert(
        PathAliasing.subprocessEnv(Map.empty, cwd, workspace)(
          EnvVars.OS_LIB_PATH_RELATIVIZER_BASE
        ) == ""
      )
    }

    test("subprocessPathSerializer") {
      val workspace = os.temp.dir()
      val out = workspace / "out"
      val dest = out / "compile.dest"
      val cwd = dest / "sandbox"
      val source = workspace / "src" / "Main.scala"

      val serialized = PathAliasing.withSubprocessPathSerializer(
        cwd,
        workspace,
        taskDest = Some(dest)
      ) {
        source.toString
      }
      val absolute = PathAliasing.withSubprocessPathSerializer(
        workspace,
        workspace,
        taskDest = Some(dest)
      ) {
        source.toString
      }

      assert(
        serialized == s"..${java.io.File.separator}..${java.io.File.separator}mill-workspace${java.io.File.separator}src${java.io.File.separator}Main.scala",
        absolute == PathRef.realAbs(source)
      )
    }

    test("subprocessBaseEnvClearsRelativizer") {
      val env = PathAliasing.subprocessBaseEnv(
        Map(EnvVars.OS_LIB_PATH_RELATIVIZER_BASE -> "inherited", "KEEP" -> "value")
      )
      assert(
        env("KEEP") == "value",
        env(EnvVars.OS_LIB_PATH_RELATIVIZER_BASE) == ""
      )
    }

    test("spawnAliasHookPreparesAliasesInTaskDest") {
      if (!scala.util.Properties.isWin) {
        val workspace = os.temp.dir()
        val out = workspace / "out"
        val outsideCwd = workspace / "src"
        val insideCwd = out / "compile.dest" / "nested"
        os.makeDir.all(outsideCwd)
        os.makeDir.all(insideCwd)

        PathAliasing.withSpawnAliasHook(workspace, out) {
          os.proc("sh", "-c", "true").call(cwd = outsideCwd)
          os.proc("sh", "-c", "true").call(cwd = insideCwd)
        }

        assert(
          os.isLink(out / PathAliasing.workspaceAliasName),
          os.isLink(out / PathAliasing.homeAliasName)
        )
      }
    }

    test("spawnAliasHookDoesNotTouchWorkspaceParentAliases") {
      if (!scala.util.Properties.isWin) {
        val root = os.temp.dir()
        val workspace = root / "workspace"
        val out = workspace / "out"
        val existingWorkspaceTarget = root / "existing-workspace-target"
        val existingHomeTarget = root / "existing-home-target"
        os.makeDir.all(workspace)
        os.makeDir.all(existingWorkspaceTarget)
        os.makeDir.all(existingHomeTarget)

        val parentWorkspaceAlias = root / PathAliasing.workspaceAliasName
        val parentHomeAlias = root / PathAliasing.homeAliasName
        val existingWorkspaceTargetNio = existingWorkspaceTarget.wrapped.toAbsolutePath.normalize()
        val existingHomeTargetNio = existingHomeTarget.wrapped.toAbsolutePath.normalize()
        java.nio.file.Files.createSymbolicLink(
          parentWorkspaceAlias.wrapped,
          existingWorkspaceTargetNio
        )
        java.nio.file.Files.createSymbolicLink(parentHomeAlias.wrapped, existingHomeTargetNio)

        PathAliasing.withSpawnAliasHook(workspace, out) {
          os.proc("sh", "-c", "true").call(cwd = workspace)
        }

        assert(
          java.nio.file.Files.readSymbolicLink(parentWorkspaceAlias.wrapped) ==
            existingWorkspaceTargetNio,
          java.nio.file.Files.readSymbolicLink(parentHomeAlias.wrapped) == existingHomeTargetNio
        )
      }
    }
  }
}
