package mill.internal

import mill.constants.DaemonFiles

import scala.jdk.OptionConverters.RichOptional

private[mill] object LauncherOutFilesRecordStore {
  case class Record(runId: String, pid: Long, command: String, startMillis: Option[Long])

  def path(out: os.Path, runId: String): os.Path =
    out / os.RelPath(DaemonFiles.perLauncherFilePath(runId))

  private def processStartMillis(pid: Long): Option[Long] =
    java.lang.ProcessHandle.of(pid).toScala
      .flatMap(_.info().startInstant().toScala.map(_.toEpochMilli))

  def write(out: os.Path, runId: String, pid: Long, command: String): Unit = {
    val commandJson = ujson.write(ujson.Str(command))
    val startMillisJson = processStartMillis(pid).fold("null")(_.toString)
    val json = s"""{"pid":$pid,"command":$commandJson,"startMillis":$startMillisJson}"""
    val file = path(out, runId)
    try mill.api.BuildCtx.withFilesystemCheckerDisabled {
        os.makeDir.all(file / os.up)
        os.write.over(file, json)
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
        .flatMap(readActiveRecord(_, removeStale, keepUnreadable))
        .sortBy(record => runIdSortKey(record.runId))
  }

  // RunIds are `<millis>-<pid>-<counter>`; sort by millis then trailing
  // counter so most-recent comes last. Older single-segment formats fall
  // back to (0, 0).
  def runIdSortKey(runId: String): (Long, Long) = {
    val parts = runId.split('-')
    if (parts.isEmpty) (0L, 0L)
    else (
      parts.head.toLongOption.getOrElse(0L),
      parts.last.toLongOption.getOrElse(0L)
    )
  }

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
          startMillis = json.get("startMillis").flatMap {
            case ujson.Null => None
            case v => v.numOpt.map(_.toLong)
          }
        )
      } catch {
        case _: Throwable if keepUnreadable => Some(Record(file.baseName, -1L, "", None))
        case _: Throwable => None
      }

    def liveProcess(record: Record): Boolean =
      java.lang.ProcessHandle.of(record.pid).toScala.exists { ph =>
        if (!ph.isAlive) false
        else record.startMillis match {
          // No recorded start time (legacy file) — fall back to PID-only check.
          case None => true
          // Verify the start time matches; otherwise the PID has been recycled
          // by a different process and the record is stale.
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
