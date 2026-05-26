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
          val runId = s"run-${i}_2026-05-26_09-00-00_pid-$pid"
          LauncherOutFilesRecordStore.write(out, runId, pid, s"active-$i")
          os.makeDir.all(out / LauncherOutFilesState.runRootDirName / runId)
        }

        val finishedRunId = s"run-0_2026-05-26_09-00-01_pid-$pid"
        val outFiles = new LauncherOutFilesImpl(
          out = out,
          activeCommandMessage = "finished",
          launcherPid = pid,
          outFilesState = outFilesState,
          runId = finishedRunId
        )
        val finishedRunDir = out / LauncherOutFilesState.runRootDirName / finishedRunId
        os.write.over(os.Path(outFiles.profile), "[]", createFolders = true)
        outFiles.close()

        assert(os.exists(finishedRunDir))
        assert(os.exists(out / OutFiles.millProfile, followLinks = true))
      } finally os.remove.all(out)
    }

    test("newRunPublishesFreshOutFilesAtStartup") {
      val out = os.temp.dir(prefix = "mill-launcher-out-files")
      try {
        val outFilesState = new LauncherOutFilesState
        val pid = ProcessHandle.current().pid()
        val firstRun = new LauncherOutFilesImpl(
          out = out,
          activeCommandMessage = "first",
          launcherPid = pid,
          outFilesState = outFilesState,
          runId = outFilesState.nextRunId()
        )
        os.write.over(os.Path(firstRun.profile), """{"old":true}""")
        os.write.over(os.Path(firstRun.chromeProfile), """{"old":true}""")
        os.write.over(os.Path(firstRun.dependencyTree), """{"old":true}""")
        os.write.over(os.Path(firstRun.invalidationTree), """{"old":true}""")

        assert(os.read(out / OutFiles.millProfile) == """{"old":true}""")
        assert(os.read(out / OutFiles.millChromeProfile) == """{"old":true}""")

        val secondRun = new LauncherOutFilesImpl(
          out = out,
          activeCommandMessage = "second",
          launcherPid = pid,
          outFilesState = outFilesState,
          runId = outFilesState.nextRunId()
        )

        assert(os.read(out / OutFiles.millProfile) == "[]\n")
        assert(os.read(out / OutFiles.millChromeProfile) == "[]\n")
        assert(os.read(out / OutFiles.millDependencyTree) == "{}\n")
        assert(os.read(out / OutFiles.millInvalidationTree) == "{}\n")

        firstRun.close()
        assert(os.read(out / OutFiles.millProfile) == "[]\n")
        assert(os.read(out / OutFiles.millChromeProfile) == "[]\n")

        secondRun.close()
      } finally os.remove.all(out)
    }

    test("malformedRecordsArePreservedAsActiveDuringSweep") {
      val out = os.temp.dir(prefix = "mill-launcher-records")
      try {
        val runId = "run-0_2026-05-26_09-00-00_pid-123"
        val record = LauncherOutFilesRecordStore.path(out, runId)
        os.write.over(record, "{", createFolders = true)

        val active = LauncherOutFilesRecordStore.sweepActive(out)

        assert(active.exists(_.runId == runId))
        assert(os.exists(record))
      } finally os.remove.all(out)
    }

    test("mostRecentActiveUsesRecordCreationTime") {
      val out = os.temp.dir(prefix = "mill-launcher-records")
      try {
        val pid = ProcessHandle.current().pid()
        val older = "run-99_2026-05-26_09-00-01_pid-123"
        val newer = "run-0_2026-05-26_09-00-00_pid-123"
        LauncherOutFilesRecordStore.write(out, older, pid, "older")
        LauncherOutFilesRecordStore.write(out, newer, pid, "newer")
        overwriteCreatedMillis(out, older, createdMillis = 1000L)
        overwriteCreatedMillis(out, newer, createdMillis = 2000L)

        assert(LauncherOutFilesRecordStore.mostRecentActive(out).map(_.runId) == Some(newer))
      } finally os.remove.all(out)
    }

    test("runIdsAreReadable") {
      val runId = new LauncherOutFilesState().nextRunId()

      assert(runId.matches(raw"run-\d+_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_pid-\d+"))
    }
  }

  private def overwriteCreatedMillis(out: os.Path, runId: String, createdMillis: Long): Unit = {
    val recordPath = LauncherOutFilesRecordStore.path(out, runId)
    val json = ujson.read(os.read(recordPath)).obj
    json("createdMillis") = ujson.Num(createdMillis)
    os.write.over(recordPath, ujson.write(json))
  }
}
