package mill.main.sbt

import sbt.Keys.target
import sbt.io.syntax._
import sbt.{AutoPlugin, IO, Setting, taskKey}
import upickle.default._

object ProjectTreePlugin extends AutoPlugin {
  // override def requires = ???
  object autoImport {
    val millInitGenerateProjectTree = taskKey[File]("generate the project tree for `mill init`")
  }
  import autoImport._

  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    millInitGenerateProjectTree := {
      // TODO
      val projectTree = ProjectTree()

      val outputFile = target.value / "mill-init-project-tree.json"
      IO.write(outputFile, write(projectTree))
      outputFile
    }
  )
}
