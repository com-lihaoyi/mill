package mill.internal

import mill.constants.OutFiles
import utest.*

object LauncherOutFilesImplTests extends TestSuite {
  val tests: Tests = Tests {
    test("cleanupDoesNotDeleteJustFinishedRunWhenActiveRunsFillRetention") {
      val out = os.temp.dir(prefix = "mill-launcher-out-files")
      try {
        val outFilesState = new LauncherOutFilesState
        val pid = ProcessHandle.current().pid()

        for (i <- 0 until 10) {
          val runId = s"1000-$pid-$i"
          LauncherOutFilesRecordStore.write(out, runId, pid, s"active-$i")
          os.makeDir.all(out / LauncherOutFilesState.runRootDirName / runId)
        }

        val finishedRunId = s"2000-$pid-0"
        val outFiles = new LauncherOutFilesImpl(
          out = out,
          activeCommandMessage = "finished",
          launcherPid = pid,
          outFilesState = outFilesState,
          runId = finishedRunId
        )
        val finishedRunDir = out / LauncherOutFilesState.runRootDirName / finishedRunId
        os.write(os.Path(outFiles.profile), "[]", createFolders = true)
        outFiles.publishArtifacts()
        outFiles.close()

        assert(os.exists(finishedRunDir))
        assert(os.exists(out / OutFiles.millProfile, followLinks = true))
      } finally os.remove.all(out)
    }

    test("malformedRecordsArePreservedAsActiveDuringSweep") {
      val out = os.temp.dir(prefix = "mill-launcher-records")
      try {
        val runId = "1000-123-0"
        val record = LauncherOutFilesRecordStore.path(out, runId)
        os.write.over(record, "{", createFolders = true)

        val active = LauncherOutFilesRecordStore.sweepActive(out)

        assert(active.exists(_.runId == runId))
        assert(os.exists(record))
      } finally os.remove.all(out)
    }
  }
}
