package mill.main

import mill.util.ScriptTestSuite
import utest._

object JavaCompileJarTests extends ScriptTestSuite(fork = false) {
  def workspaceSlug = "java-compile-jar"
  def scriptSourcePath = os.pwd / 'main / 'test / 'resources / 'examples / 'javac
  val tests = Tests{
    initWorkspace()
    'test - {
      if (!ammonite.util.Util.java9OrAbove) {
        // Basic target evaluation works
        assert(eval("classFiles"))
        assert(eval("jar"))

        val classFiles1 = meta("classFiles")
        val jar1 = meta("jar")

        assert(eval("classFiles"))
        assert(eval("jar"))

        // Repeated evaluation has the same results
        val classFiles2 = meta("classFiles")
        val jar2 = meta("jar")

        assert(
          jar1 == jar2,
          classFiles1 == classFiles2
        )

        // If we update resources, classFiles are unchanged but jar changes
        for(scalaFile <- os.walk(workspacePath).filter(_.ext == "txt")){
          os.write.append(scalaFile, "\n")
        }

        assert(eval("classFiles"))
        assert(eval("jar"))

        val classFiles3 = meta("classFiles")
        val jar3 = meta("jar")

        assert(
          jar2 != jar3,
          classFiles2 == classFiles3
        )

        // We can intentionally break the code, have the targets break, then
        // fix the code and have them recover.
        for(scalaFile <- os.walk(workspacePath).filter(_.ext == "java")){
          os.write.append(scalaFile, "\n}")
        }

        assert(!eval("classFiles"))
        assert(!eval("jar"))

        for(scalaFile <- os.walk(workspacePath).filter(_.ext == "java")){
          os.write.over(scalaFile, os.read(scalaFile).dropRight(2))
        }

        assert(eval("classFiles"))
        assert(eval("jar"))
      }
    }
  }
}

