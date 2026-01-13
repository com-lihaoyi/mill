package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object CompileErrorFormattingTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    integrationTest { tester =>
      locally {
        val res = tester.eval("java-type-unchecked.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[warn] java-type-unchecked/src/Foo.java:6:22",
          "        return (T[]) obj;",
          "                     ^^^",
          "unchecked cast"
        )
      }

      locally {
        val res = tester.eval("java-type-mismatch.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] java-type-mismatch/src/Foo.java:5:17",
          "        int x = \"hello\";",
          "                ^^^^^^^",
          "incompatible types: java.lang.String cannot be converted to int"
        )
      }

      locally {
        val res = tester.eval("java-type-method.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] java-type-method/src/Foo.java:6:10",
          "        s.nonExistentMethod();",
          "         ^^^^^^^^^^^^^^^^^^",
          "cannot find symbol"
        )
      }

      locally {
        val res = tester.eval("java-type-variable.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] java-type-variable/src/Foo.java:5:17",
          "        int x = undefinedVariable + 1;",
          "                ^^^^^^^^^^^^^^^^^",
          "cannot find symbol"
        )
      }

      locally {
        val res = tester.eval("java-parse-semicolon.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] java-parse-semicolon/src/Foo.java:5:18",
          "        int x = 1",
          "                 ^",
          "';' expected"
        )
      }

      locally {
        val res = tester.eval("java-parse-string.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] java-parse-string/src/Foo.java:5:20",
          "        String s = \"hello world",
          "                   ^",
          "unclosed string literal"
        )
      }

      locally {
        val res = tester.eval("java-parse-toplevel.compile")
        assert(!res.isSuccess)
        // This error doesn't have a caret - just file:line:col, source, empty, message
        res.assertContainsLines(
          "[error] java-parse-toplevel/src/Foo.java:3:0",
          "int x = 1;",
          "",
          "unnamed classes are a preview feature and are disabled by default."
        )
      }
      locally {
        val res = tester.eval("scala-type-mismatch.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] scala-type-mismatch/src/Foo.scala:5:18",
          "    val x: Int = \"hello\"",
          "                 ^^^^^^^",
          "Found:    (\"hello\" : String)",
          "Required: Int"
        )
      }

      locally {
        val res = tester.eval("scala-type-method.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] scala-type-method/src/Foo.scala:6:7",
          "    s.nonExistentMethod()",
          "      ^^^^^^^^^^^^^^^^^",
          "value nonExistentMethod is not a member of String"
        )
      }

      locally {
        val res = tester.eval("scala-type-variable.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] scala-type-variable/src/Foo.scala:5:13",
          "    val x = undefinedVariable + 1",
          "            ^^^^^^^^^^^^^^^^^",
          "Not found: undefinedVariable"
        )
      }

      locally {
        val res = tester.eval("scala-parse-semicolon.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] scala-parse-semicolon/src/Foo.scala:6:15",
          "    val y = x +",
          "              ^",
          "end of statement expected but identifier found"
        )
      }

      locally {
        val res = tester.eval("scala-parse-string.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] scala-parse-string/src/Foo.scala:5:13",
          "    val s = \"hello world",
          "            ^",
          "unclosed string literal"
        )
      }

      locally {
        val res = tester.eval("scala-parse-toplevel.compile")
        assert(!res.isSuccess)
        res.assertContainsLines(
          "[error] scala-parse-toplevel/src/Foo.scala:6:7",
          "class class Foo {",
          "      ^^^^^",
          "an identifier expected, but 'class' found"
        )
      }
    }
  }
}
