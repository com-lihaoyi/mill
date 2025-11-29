package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

// Make sure script header changes are appropriately picked up: whether from success to
// failure, failure to success, success to success, or failure to different kind of failure
object ScriptHeaderChanges extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      assert(res.out.contains("Hello"))
      assert(res.isSuccess)

      tester.modifyFile(tester.workspacePath / "Foo.java", _.replace("//", "//|"))

      val res2 = tester.eval("./Foo.java")
      assert(!res2.isSuccess)
      assert(res2.err.contains(
        "invalid build config in Foo.java:1 key \"invalid\" does not override any task"
      ))

      tester.modifyFile(
        tester.workspacePath / "Foo.java",
        _.replace("invalid: key", "mvnDeps: key")
      )

      val res3 = tester.eval("./Foo.java")
      assert(!res3.isSuccess)
      assert(res3.err.contains(
        "Foo.java:mvnDeps Failed de-serializing config override at Foo.java:1 expected sequence got string"
      ))

      tester.modifyFile(tester.workspacePath / "Foo.java", _.replace("//|", "//"))
      val res4 = tester.eval("./Foo.java")
      assert(res4.out.contains("Hello"))
      assert(res4.isSuccess)

      tester.modifyFile(
        tester.workspacePath / "Foo.java",
        _.replace("// mvnDeps: key", "//| mvnDeps: [key]")
      )

      val res5 = tester.eval("./Foo.java")
      assert(!res5.isSuccess)
      assert(res5.err.contains(
        "Foo.java:mvnDeps Failed de-serializing config override at Foo.java:1 Unable to parse signature: [key]"
      ))

      tester.modifyFile(tester.workspacePath / "Foo.java", _.replace("//|", "//"))
      val res6 = tester.eval("./Foo.java")
      assert(res6.out.contains("Hello"))
      assert(res6.isSuccess)

      tester.modifyFile(
        tester.workspacePath / "Foo.java",
        _.replace("// mvnDeps: [key]", "//| mvnDeps: []")
      )
      val res7 = tester.eval("./Foo.java")
      assert(res7.out.contains("Hello"))
      assert(res7.isSuccess)

      val res8 = tester.eval(("show", "./Foo.java:mvnDeps"))
      assert(res8.out.contains("[]"))
      assert(res8.isSuccess)

      tester.modifyFile(
        tester.workspacePath / "Foo.java",
        _.replace("//| mvnDeps: []", "//| mvnDeps: [org.thymeleaf:thymeleaf:3.1.1.RELEASE]")
      )

      val res9 = tester.eval(("show", "./Foo.java:mvnDeps"))
      assert(res9.out.contains("\"org.thymeleaf:thymeleaf:3.1.1.RELEASE\""))
      assert(res9.isSuccess)

      tester.modifyFile(
        tester.workspacePath / "Foo.java",
        _.replace("//| mvnDeps: [org.thymeleaf:thymeleaf:3.1.1.RELEASE]", "")
      )

      val res10 = tester.eval(("show", "./Foo.java:mvnDeps"))
      assert(!res10.out.contains("\"org.thymeleaf:thymeleaf:3.1.1.RELEASE\""))
      assert(res10.isSuccess)
    }
  }
}
