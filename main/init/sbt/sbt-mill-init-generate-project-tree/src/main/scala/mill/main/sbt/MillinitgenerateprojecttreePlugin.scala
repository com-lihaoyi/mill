package mill.main.sbt

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

object MillinitgenerateprojecttreePlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    val exampleSetting = settingKey[String]("A setting that is automatically imported to the build")
    val exampleTask = taskKey[String]("A task that is automatically imported to the build")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    exampleSetting := "just an example",
    exampleTask := "computed from example setting: " + exampleSetting.value
  )

  override lazy val buildSettings = Seq()

  override lazy val globalSettings = Seq()
}
