package mill.main.sbt

import sbt.Keys.target
import sbt.io.syntax.*
import sbt.{AutoPlugin, IO, Setting, taskKey}
import upickle.default.*

object ProjectTreePlugin extends AutoPlugin {
  override def trigger = allRequirements
  // override def requires = ??? // defaults to `JvmPlugin`

  object autoImport {
    val millInitGenerateProjectTree = taskKey[File]("generate the project tree for `mill init`")
  }

  import autoImport.*

  // `target.value` doesn't work in `globalSettings` and `buildSettings`
  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    millInitGenerateProjectTree := {
      // TODO
      val projectTree = ProjectTree()

      val outputFile = target.value / "mill-init-project-tree.json"
      IO.write(outputFile, write(projectTree))
      outputFile
    }
  )
}
