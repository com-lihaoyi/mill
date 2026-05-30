package mill.internal

import mill.constants.DaemonFiles

import scala.jdk.OptionConverters.RichOptional

private[mill] object LauncherOutFilesUtils {
  case class Record(
      runId: String,
      pid: Long,
      command: String,
      processStartMillis: Option[Long],
      createdMillis: Long
  )

  def path(out: os.Path, runId: String): os.Path =
    out / os.RelPath(DaemonFiles.perLauncherFilePath(runId))

  private def processStartMillis(pid: Long): Option[Long] =
    java.lang.ProcessHandle.of(pid).toScala
      .flatMap(_.info().startInstant().toScala.map(_.toEpochMilli))

  def write(out: os.Path, runId: String, pid: Long, command: String): Unit = {
    val commandJson = ujson.write(ujson.Str(command))
    val startMillisJson = processStartMillis(pid).fold("null")(_.toString)
    val createdMillis = System.currentTimeMillis()
    val json =
      s"""{"pid":$pid,"command":$commandJson,"startMillis":$startMillisJson,"createdMillis":$createdMillis}"""
    val file = path(out, runId)
    try mill.api.BuildCtx.withFilesystemCheckerDisabled {
        val dir = file / os.up
        os.makeDir.all(dir)
        // Publish the record atomically so concurrent scanners never observe a
        // truncated or partially-written file: write to a temp file in the same
        // directory, then atomically rename it over the target.
        val tmp = dir / s".${file.last}.tmp-${System.nanoTime()}"
        try {
          os.write.over(tmp, json)
          java.nio.file.Files.move(
            tmp.toNIO,
            file.toNIO,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
          )
        } finally {
          try os.remove(tmp, checkExists = false)
          catch { case _: Throwable => () }
        }
      }
    catch { case _: Throwable => () }
  }

  def remove(out: os.Path, runId: String): Unit =
    try mill.api.BuildCtx.withFilesystemCheckerDisabled(os.remove(
        path(out, runId),
        checkExists = false
      ))
    catch { case _: Throwable => () }

  def sweepActive(out: os.Path): Seq[Record] = scanActive(out, removeStale = true)

  def mostRecentActive(out: os.Path): Option[Record] =
    scanActive(out, removeStale = false, keepUnreadable = false).lastOption

  private def scanActive(
      out: os.Path,
      removeStale: Boolean,
      keepUnreadable: Boolean = true
  ): Seq[Record] = {
    val dir = out / os.RelPath(DaemonFiles.millRun)
    if (!os.exists(dir)) Nil
    else
      os.list(dir)
        .filter(os.isFile(_))
        .flatMap(file => readActiveRecord(file, removeStale, keepUnreadable).map(file -> _))
        .sortBy { case (file, record) =>
          (record.createdMillis, lastModifiedMillis(file))
        }
        .map(_._2)
  }

  private def lastModifiedMillis(path: os.Path): Long =
    try os.mtime(path)
    catch { case _: Throwable => 0L }

  private def readActiveRecord(
      file: os.Path,
      removeStale: Boolean,
      keepUnreadable: Boolean
  ): Option[Record] = {
    val recordOpt =
      try {
        val json = ujson.read(os.read(file)).obj
        for {
          pid <- json.get("pid").map(_.num.toLong)
        } yield Record(
          runId = file.baseName,
          pid = pid,
          command = json.get("command").map(_.str).getOrElse(""),
          processStartMillis = json.get("startMillis").flatMap {
            case ujson.Null => None
            case v => v.numOpt.map(_.toLong)
          },
          createdMillis = json.get("createdMillis").flatMap(_.numOpt.map(_.toLong))
            .getOrElse(lastModifiedMillis(file))
        )
      } catch {
        case _: Throwable if keepUnreadable =>
          Some(Record(file.baseName, -1L, "", None, lastModifiedMillis(file)))
        case _: Throwable => None
      }

    def liveProcess(record: Record): Boolean =
      java.lang.ProcessHandle.of(record.pid).toScala.exists { ph =>
        if (!ph.isAlive) false
        else record.processStartMillis match {
          // Legacy record without start time — PID-only check.
          case None => true
          // Defeat PID reuse: a recycled PID with a different start
          // time means the original record is stale.
          case Some(recorded) =>
            ph.info().startInstant().toScala.map(_.toEpochMilli).contains(recorded)
        }
      }

    recordOpt match {
      case Some(record) if record.pid == -1L && keepUnreadable =>
        Some(record)
      case Some(record) if liveProcess(record) =>
        Some(record)
      case _ =>
        if (removeStale) {
          try os.remove(file, checkExists = false)
          catch { case _: Throwable => () }
        }
        None
    }
  }

}
