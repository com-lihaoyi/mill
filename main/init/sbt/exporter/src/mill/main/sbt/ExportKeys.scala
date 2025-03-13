package mill.main.sbt

import sbt.{File, settingKey, taskKey}

object ExportKeys {
  val millInitExport = taskKey[Unit]("")
  val millInitExportFile = settingKey[File]("")
  val millInitProjectModel = taskKey[SbtProjectModel]("")
}
