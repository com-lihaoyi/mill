package mill.eval

import mill.api.BuildCtx
import utest.*

object ScriptModuleInitTests extends TestSuite {
  val tests = Tests {
    test("directoryProbeIsNotWatched") {
      val workspace = os.temp.dir()
      os.makeDir(workspace / "module")

      val watchedValues = BuildCtx.evalWatchedValues
      watchedValues.synchronized {
        val previousWatchedValues = watchedValues.toVector
        try {
          watchedValues.clear()
          BuildCtx.workspaceRoot0.withValue(workspace) {
            val result = ScriptModuleInit().resolveScriptModule("module", null)
            assert(result.isEmpty)
          }
          assert(watchedValues.isEmpty)

          BuildCtx.workspaceRoot0.withValue(workspace) {
            val result = ScriptModuleInit().resolveScriptModule("missing", null)
            assert(result.isEmpty)
          }
          assert(watchedValues.size == 1)
        } finally {
          watchedValues.clear()
          watchedValues ++= previousWatchedValues
        }
      }
    }
  }
}
