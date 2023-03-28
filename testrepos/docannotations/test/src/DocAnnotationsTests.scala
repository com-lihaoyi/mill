package mill.integration

import utest._

object DocAnnotationsTests extends IntegrationTestSuite.Cross {
  val tests = Tests {
    initWorkspace()
    "test" - {
      val res = eval("inspect", "core.test.ivyDeps")
      assert(res == true)
      val inheritedIvyDeps = ujson.read(meta("inspect"))("value").str
      assert(
        inheritedIvyDeps.contains("core.test.ivyDeps"),
        inheritedIvyDeps.contains("Overriden ivyDeps Docs!!!"),
        inheritedIvyDeps.contains("Any ivy dependencies you want to add to this Module")
      )

      assert(eval("inspect", "core.task"))
      val task = ujson.read(meta("inspect"))("value").str
      assert(
        task.contains("Core Task Docz!")
      )

      assert(eval("inspect", "inspect"))
      val doc = ujson.read(meta("inspect"))("value").str
      assert(
        doc.contains("Displays metadata about the given task without actually running it.")
      )
    }
  }
}
