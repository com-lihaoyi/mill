package mill.integration

import mill.util.ScriptTestSuite
import utest._

abstract class IntegrationTestSuite(repoKey: String, val workspaceSlug: String, fork: Boolean)
    extends ScriptTestSuite(fork) {
  val buildFilePath = os.pwd / "integration" / "test" / "resources" / workspaceSlug
  def scriptSourcePath = {
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    val path = sys.props(repoKey)
    val Seq(wrapper) = os.list(os.Path(path))
    wrapper
  }

  def buildFiles: Seq[os.Path] = os.walk(buildFilePath)

  override def initWorkspace() = {
    val path = super.initWorkspace()
    buildFiles.foreach { file =>
      os.copy.over(file, workspacePath / file.last)
    }
    os.walk(workspacePath).foreach { p =>
      if (p.last == "AsMapTest.java") {
        os.write.over(
          p,
          os.read
            .lines(p)
            .map(
              _.replace(
                "map.keySet().toArray(null);",
                "map.keySet().toArray((Integer[]) null);"
              )
                .replace(
                  "map.values().toArray(null);",
                  "map.values().toArray((Integer[]) null);"
                )
                .replace(
                  "map.entrySet().toArray(null);",
                  "map.entrySet().toArray((Integer[]) null);"
                )
            )
            .mkString("\n")
        )
      } else if (p.last == "EmptyCachesTest.java") {
        os.write.over(
          p,
          os.read
            .lines(p)
            .map(
              _.replace("keys.toArray(null);", "keys.toArray((Object[]) null);")
                .replace(
                  "values.toArray(null);",
                  "values.toArray((Object[]) null);"
                )
                .replace(
                  "entries.toArray(null);",
                  "entries.toArray((Entry<Object, Object>[]) null);"
                )
            )
            .mkString("\n")
        )
      }
    }
    assert(!os.walk(workspacePath).exists(_.ext == "class"))
    path
  }
}
