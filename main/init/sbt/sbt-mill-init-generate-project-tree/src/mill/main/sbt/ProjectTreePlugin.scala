package mill.main.sbt

import sbt.Keys.target
import sbt.{AutoPlugin, IO, Setting, taskKey}
import sbt.io.syntax.*

object ProjectTreePlugin extends AutoPlugin {
  // override def requires = ???
  object autoImport {
    val millInitGenerateProjectTree = taskKey[File]("generate the project tree for `mill init`")
  }
  import autoImport.*

  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    millInitGenerateProjectTree := {
      ProjectTree()
      // TODO
      val outputFile = target.value / "mill-init-project-tree.json"
      IO.write(outputFile, "test")
      outputFile
    }
  )
}
