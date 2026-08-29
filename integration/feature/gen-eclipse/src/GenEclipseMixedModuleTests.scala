package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import os.Path
import utest.{Tests, test}

object GenEclipseMixedModuleTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path =
    super.workspaceSourcePath / "mixed-java-scala-project"

  def tests: Tests = Tests {
    test("millRepoModuleShape") - integrationTest { tester =>
      import tester.*

      val ret = eval("mill.eclipse/", check = true)
      assert(ret.exitCode == 0)

      val distRawPath = workspacePath / "dist" / "raw"
      val project = scala.xml.XML.loadFile((distRawPath / ".project").toIO)
      assert((project \ "name").text == "dist.raw")
      assert((project \\ "nature").exists(_.text == "org.eclipse.jdt.core.javanature"))

      val classpath = scala.xml.XML.loadFile((distRawPath / ".classpath").toIO)
      val entries = classpath \ "classpathentry"
      val projectDependencies = entries.filter(node => (node \@ "kind") == "src")
        .map(_ \@ "path")
        .filter(_.startsWith("/"))
      val libraries = entries.filter(node => (node \@ "kind") == "lib").map(_ \@ "path")

      assert(projectDependencies.isEmpty)
      val scalaClasses = libraries.filter(
        _.replace('\\', '/').endsWith("runner/launcher/compile.dest/classes")
      )
      assert(scalaClasses.size == 1)

      val classesPath = distRawPath.toNIO.resolve(scalaClasses.head).normalize()
      assert(java.nio.file.Files.exists(classesPath.resolve("dependency/Greeting.class")))
    }
  }
}
