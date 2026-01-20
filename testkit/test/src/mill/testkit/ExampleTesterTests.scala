package mill.testkit

import utest.*

object ExampleTesterTests extends TestSuite {

  def tests: Tests = Tests {
    test("parseShellArgs") {
      val parse = ExampleTester.parseShellArgs

      test("simple") {
        assert(parse("foo bar baz") == Seq("foo", "bar", "baz"))
      }
      test("extraWhitespace") {
        assert(parse("  foo   bar  ") == Seq("foo", "bar"))
      }
      test("tabs") {
        assert(parse("foo\tbar\t\tbaz") == Seq("foo", "bar", "baz"))
      }
      test("empty") {
        assert(parse("") == Seq())
        assert(parse("   ") == Seq())
      }
      test("singleQuoted") {
        assert(parse("'hello world'") == Seq("hello world"))
        assert(parse("foo 'bar baz' qux") == Seq("foo", "bar baz", "qux"))
      }
      test("doubleQuoted") {
        assert(parse("\"hello world\"") == Seq("hello world"))
        assert(parse("foo \"bar baz\" qux") == Seq("foo", "bar baz", "qux"))
      }
      test("mixedQuotes") {
        assert(parse("'single' \"double\"") == Seq("single", "double"))
        assert(parse("foo'bar'baz") == Seq("foobarbaz"))
        assert(parse("foo\"bar\"baz") == Seq("foobarbaz"))
      }
      test("escapedSpaceUnquoted") {
        assert(parse("hello\\ world") == Seq("hello world"))
        assert(parse("foo bar\\ baz qux") == Seq("foo", "bar baz", "qux"))
      }
      test("escapedQuoteInDouble") {
        assert(parse("\"hello \\\"world\\\"\"") == Seq("hello \"world\""))
      }
      test("escapedBackslashInDouble") {
        assert(parse("\"hello\\\\world\"") == Seq("hello\\world"))
      }
      test("singleQuotePreservesBackslash") {
        // In bash, single quotes preserve everything literally
        assert(parse("'hello\\world'") == Seq("hello\\world"))
        assert(parse("'hello\\'") == Seq("hello\\"))
      }
      test("globPattern") {
        // Real-world case: test patterns with wildcards
        assert(parse("foo.test 'foo.FooMoreTests.*'") == Seq("foo.test", "foo.FooMoreTests.*"))
        assert(parse("foo.test foo.FooMoreTests.*") == Seq("foo.test", "foo.FooMoreTests.*"))
      }
    }
    test("nodaemon") {
      val workspacePath = ExampleTester.run(
        daemonMode = false,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      assert(os.exists(workspacePath / "out/mill-no-daemon"))

      assert(TestkitTestUtils.getProcessIdFiles(workspacePath).isEmpty)
    }

    test("daemon") {
      val workspacePath = ExampleTester.run(
        daemonMode = true,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      assert(os.exists(workspacePath / "out/mill-daemon"))

      assert(TestkitTestUtils.getProcessIdFiles(workspacePath).isEmpty)
    }
  }
}
