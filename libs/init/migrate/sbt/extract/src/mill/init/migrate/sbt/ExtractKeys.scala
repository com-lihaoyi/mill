package mill.init.migrate
package sbt

import _root_.sbt.{File, settingKey, taskKey}

object ExtractKeys {
  val millInitExtractArgs = settingKey[(File, String)]("")
  val millInitExtract = taskKey[Unit]("")
  val millInitMetaModule = taskKey[MetaModule[SbtModuleMetadata]]("")
}
