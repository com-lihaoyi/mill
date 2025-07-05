package mill.init.importer.sbt

import sbt.{File, settingKey, taskKey}

object ExportKeys {

  val millInitExportFile = settingKey[File]("")
  val millInitExport = taskKey[Unit]("")
  val millInitExportedProject = taskKey[ExportedSbtProject]("")
}
