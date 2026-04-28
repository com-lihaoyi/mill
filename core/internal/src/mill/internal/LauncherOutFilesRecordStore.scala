package mill.internal

import mill.constants.DaemonFiles

import scala.jdk.OptionConverters.RichOptional

private[mill] object LauncherOutFilesRecordStore {
  case class Record(runId: String, pid: Long, command: String)

  def path(out: os.Path, runId: String): os.Path =
    out / os.RelPath(DaemonFiles.perLauncherFilePath(runId))

  def write(out: os.Path, runId: String, pid: Long, command: String): Unit = {
    val commandJson = ujson.write(ujson.Str(command))
    val json = s"""{"pid":$pid,"command":$commandJson}"""
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
          command = json.get("command").map(_.str).getOrElse("")
        )
      } catch {
        case _: Throwable if keepUnreadable => Some(Record(file.baseName, -1L, ""))
        case _: Throwable => None
      }

    recordOpt match {
      case Some(record) if record.pid == -1L && keepUnreadable =>
        Some(record)
      case Some(record) if java.lang.ProcessHandle.of(record.pid).toScala.exists(_.isAlive) =>
        Some(record)
      case _ =>
        if (removeStale) {
          try os.remove(file, checkExists = false)
          catch { case _: Throwable => () }
        }
        None
    }
  }

  // RunIds are `<millis>-<pid>-<counter>`; sort by millis then trailing
  // counter so most-recent comes last. Older single-segment formats fall
  // back to (0, 0).
  private def runIdSortKey(runId: String): (Long, Long) = {
    val parts = runId.split('-')
    if (parts.isEmpty) (0L, 0L)
    else (
      parts.head.toLongOption.getOrElse(0L),
      parts.last.toLongOption.getOrElse(0L)
    )
  }
}
