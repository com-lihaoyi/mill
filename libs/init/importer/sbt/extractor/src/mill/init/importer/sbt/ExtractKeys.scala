package mill.init.importer.sbt

import sbt.{File, settingKey, taskKey}

object ExtractKeys {

  val millInitExtractFile = settingKey[File]("")
  val millInitExtract = taskKey[Unit]("")
  val millInitSbtProjectIR = taskKey[SbtProjectIR]("")
}
