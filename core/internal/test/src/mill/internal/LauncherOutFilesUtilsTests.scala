package mill.internal

import utest.*

import java.nio.file.Files
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * Regression tests for `LauncherOutFilesUtils.write` publishing records
 * atomically. The previous implementation used `os.write.over` (truncate then
 * write), so a concurrent scanner reading `ujson.read(os.read(file))` could
 * observe an empty or partially-written record. The write must instead be
 * published via an atomic rename so readers always see either the complete old
 * or the complete new content.
 */
object LauncherOutFilesUtilsTests extends TestSuite {

  // Large enough that an in-place truncate-then-write leaves an observable
  // window where the file is empty or contains only a prefix of the JSON.
  private val bigCommand: String = "x" * (4 * 1024 * 1024)

  val tests: Tests = Tests {

    test("concurrentReaderNeverSeesTornRecord") {
      val out = os.temp.dir(prefix = "mill-record-atomic")
      try {
        val pid = ProcessHandle.current().pid()
        val runId = "run-0_2026-05-26_09-00-00_pid-123"
        val file = LauncherOutFilesUtils.path(out, runId)

        // Seed an initial complete record so the reader always has a file to read.
        LauncherOutFilesUtils.write(out, runId, pid, bigCommand)

        val stop = new AtomicBoolean(false)
        val failure = new AtomicReference[String](null)

        val writer = new Thread(() => {
          while (!stop.get() && failure.get() == null) {
            LauncherOutFilesUtils.write(out, runId, pid, bigCommand)
          }
        })

        val reader = new Thread(() => {
          var reads = 0
          while (reads < 2000 && failure.get() == null) {
            // Mirror exactly how the production scanner reads records.
            val raw =
              try Some(os.read(file))
              catch { case _: Throwable => None }
            raw match {
              case None =>
                // os.read failing (e.g. file momentarily absent) is itself a
                // symptom the atomic publication is meant to prevent.
                failure.compareAndSet(null, "os.read(file) threw / file missing mid-write")
              case Some(text) =>
                try {
                  val json = ujson.read(text).obj
                  val command = json.get("command").map(_.str).getOrElse("")
                  if (command.length != bigCommand.length) {
                    failure.compareAndSet(
                      null,
                      s"torn record: command length ${command.length} != ${bigCommand.length}"
                    )
                  }
                } catch {
                  case t: Throwable =>
                    failure.compareAndSet(
                      null,
                      s"torn record: parse failed on ${text.length} bytes: ${t.getClass.getName}"
                    )
                }
            }
            reads += 1
          }
        })

        reader.start()
        writer.start()
        reader.join(60000)
        stop.set(true)
        writer.join(60000)

        assert(failure.get() == null)
      } finally os.remove.all(out)
    }

    test("writePublishesViaAtomicRenameNotInPlaceTruncation") {
      val out = os.temp.dir(prefix = "mill-record-atomic")
      try {
        val pid = ProcessHandle.current().pid()
        val runId = "run-0_2026-05-26_09-00-00_pid-123"
        val file = LauncherOutFilesUtils.path(out, runId)

        def fileKey(): AnyRef = {
          val attrs =
            Files.readAttributes(file.toNIO, classOf[java.nio.file.attribute.BasicFileAttributes])
          attrs.fileKey()
        }

        LauncherOutFilesUtils.write(out, runId, pid, "first")
        assert(os.exists(file))
        val key1 = fileKey()

        LauncherOutFilesUtils.write(out, runId, pid, "second")
        assert(os.exists(file))
        val key2 = fileKey()

        // The second write must reuse the freshly-renamed inode, so the file
        // identity changes. An in-place `os.write.over` would keep the same
        // inode (and hence the same fileKey). On filesystems that do not expose
        // a fileKey (key1 == null) we cannot make this assertion, so skip it.
        if (key1 != null && key2 != null) {
          assert(key1 != key2)
        }

        // Regardless of fileKey support, the published content is always complete.
        val json = ujson.read(os.read(file)).obj
        assert(json("command").str == "second")
      } finally os.remove.all(out)
    }
  }
}
