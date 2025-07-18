package mill.init.migrate
package sbt

import _root_.sbt.{File, settingKey}

object PluginKeys {

  // https://github.com/portable-scala/sbt-crossproject/blob/7fbbf6be90e012b6f765647b113846b845719218/sbt-crossproject/src/main/scala/sbtcrossproject/CrossPlugin.scala#L68
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")
}
