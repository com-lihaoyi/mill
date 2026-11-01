package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import os.Path
import utest.*

object GenIdeaScriptTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "scripts"

  def tests: Tests = Tests {
    test("scriptsWithSameFileName") - integrationTest { tester =>
      import tester.*
      eval("mill.idea.GenIdea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      val modulesDir = workspacePath / ".idea" / "mill_modules"
      val scriptModules = os.list(modulesDir).map(_.last).filter(_.startsWith("script-")).sorted
      assert(scriptModules == Seq("script-a.foo.iml", "script-b.foo.iml", "script-qux.iml"))

      val modulesXml = os.read(workspacePath / ".idea" / "modules.xml")
      for (name <- scriptModules) {
        assert(modulesXml.split(s"/mill_modules/$name\"").length - 1 == 2)
      }

      def contentOf(name: String) = os.read(modulesDir / name)
      val aFoo = contentOf("script-a.foo.iml")
      assert(aFoo.contains("file://$MODULE_DIR$/../../a/Foo.scala"))
      assert(aFoo.contains("file://$MODULE_DIR$/../../out/a/Foo.scala/compile.dest"))
      assert(!aFoo.contains("b/Foo.scala"))

      val bFoo = contentOf("script-b.foo.iml")
      assert(bFoo.contains("file://$MODULE_DIR$/../../b/Foo.scala"))
      assert(bFoo.contains("file://$MODULE_DIR$/../../out/b/Foo.scala/compile.dest"))
      assert(!bFoo.contains("a/Foo.scala"))
    }
  }
}
