package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Asserts that a user subprocess run with `cwd = BuildCtx.workspaceRoot` behaves like a normal
 * subprocess, not a Mill task-dest-scoped subprocess:
 *
 * - `OS_LIB_PATH_RELATIVIZER_BASE` is empty in the subprocess env
 * - the subprocess really ran from the workspace root
 * - existing `mill-workspace` / `mill-home` symlinks in the workspace parent are left untouched
 */
object PathRelativizationTests extends UtestIntegrationTestSuite {
  def tests: Tests = Tests {
    test("workspaceCwdDoesNotOverwriteParentAliases") - integrationTest { tester =>
      if (!scala.util.Properties.isWin) {
        val parent = tester.workspacePath / os.up
        val workspaceAlias = parent / "mill-workspace"
        val homeAlias = parent / "mill-home"
        if (os.isLink(workspaceAlias)) os.remove(workspaceAlias)
        if (os.isLink(homeAlias)) os.remove(homeAlias)

        val workspaceTarget = parent / "existing-workspace-target"
        val homeTarget = parent / "existing-home-target"
        os.makeDir.all(workspaceTarget)
        os.makeDir.all(homeTarget)

        val workspaceTargetNio = workspaceTarget.wrapped.toAbsolutePath.normalize()
        val homeTargetNio = homeTarget.wrapped.toAbsolutePath.normalize()
        java.nio.file.Files.createSymbolicLink(workspaceAlias.wrapped, workspaceTargetNio)
        java.nio.file.Files.createSymbolicLink(homeAlias.wrapped, homeTargetNio)

        try {
          val res = tester.eval("workspaceCwdRelativizer")
          assert(res.isSuccess)
          res.assertContainsLines(
            "relativizer: <empty>",
            s"cwd: ${tester.workspacePath.wrapped.toAbsolutePath.normalize()}"
          )
          assert(
            java.nio.file.Files.readSymbolicLink(workspaceAlias.wrapped) == workspaceTargetNio,
            java.nio.file.Files.readSymbolicLink(homeAlias.wrapped) == homeTargetNio
          )
        } finally {
          if (os.isLink(workspaceAlias)) os.remove(workspaceAlias)
          if (os.isLink(homeAlias)) os.remove(homeAlias)
        }
      }
    }
  }
}
