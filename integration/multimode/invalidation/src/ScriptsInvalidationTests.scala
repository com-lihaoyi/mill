package mill.integration

import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}

import utest.*

object ScriptsInvalidationTests extends UtestIntegrationTestSuite {

  def runTask(tester: IntegrationTester, task: String): Set[String] = {
    val res = tester.eval(task)
    assert(res.isSuccess)
    res.out.linesIterator.map(_.trim).toSet
  }

  val tests: Tests = Tests {
    test("should not invalidate tasks in different untouched sc files") - integrationTest {
      tester =>
        import tester.*
        // first run
        val result = runTask(tester, "task")

        val expected = Set("a", "d", "b", "c")

        assert(result == expected)

        // second run modifying script
        modifyFile(
          workspacePath / "build.mill",
          _.replace("""println("task")""", """System.out.println("task2")""")
        )

        val stdout = runTask(tester, "task")

        assert(stdout.isEmpty)
    }

    test("should invalidate tasks if leaf file is changed") - integrationTest { tester =>
      import tester.*
      // first run

      val result = runTask(tester, "task")
      val expected = Set("a", "d", "b", "c")

      assert(result == expected)

      //  second run modifying script
      modifyFile(
        workspacePath / "b/inputD.mill",
        _.replace("""println("d")""", """System.out.println("d2")""")
      )

      val result2 = runTask(tester, "task")
      val expected2 = Set("d2", "b")

      assert(result2 == expected2)

    }
    test("should handle submodules in scripts") - integrationTest { tester =>
      import tester.*
      // first run
      val result = runTask(tester, "module.task")
      val expected = Set("a", "d", "b", "c", "task")

      assert(result == expected)

      // second run modifying script
      modifyFile(
        workspacePath / "build.mill",
        _.replace("""println("task")""", """System.out.println("task2")""")
      )

      val result2 = runTask(tester, "module.task")
      val expected2 = Set("task2")

      assert(result2 == expected2)
    }
    test("should handle ammonite ^ imports") - retry(3) {
      integrationTest { tester =>
        import tester.*

        // first run
        val result = runTask(tester, "taskE")
        val expected = Set("a", "e", "taskE")

        assert(result == expected)

        // second run modifying script
        modifyFile(
          workspacePath / "build.mill",
          _.replace("""println("taskE")""", """System.out.println("taskE2")""")
        )

        val result2 = runTask(tester, "taskE")
        val expected2 = Set("taskE2")

        assert(result2 == expected2)
      }
    }
    test("should handle ammonite paths with symbols") - integrationTest { tester =>
      val result = runTask(tester, "taskSymbols")
      val expected = Set("taskSymbols")

      assert(result == expected)
    }
    test("should handle ammonite files with symbols") - integrationTest { tester =>
      val result = runTask(tester, "taskSymbolsInFile")
      val expected = Set("taskSymbolsInFile")

      assert(result == expected)
    }
  }
}
