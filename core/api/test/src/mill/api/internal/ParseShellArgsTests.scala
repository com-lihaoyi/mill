package mill.api.internal

import utest.*

object ParseShellArgsTests extends TestSuite {
  val parse = ParseArgs.parseShellArgs

  def tests: Tests = Tests {
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
}
