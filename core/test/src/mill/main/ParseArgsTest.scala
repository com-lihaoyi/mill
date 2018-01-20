package mill.main

import mill.define.Segment
import mill.define.Segment.{Cross, Label}
import utest._

object ParseArgsTest extends TestSuite {

  val tests = Tests {
    'extractSelsAndArgs - {
      def check(input: Seq[String],
                expectedSelectors: Seq[String],
                expectedArgs: Seq[String],
                expectedIsMulti: Boolean) = {
        val (selectors, args, isMulti) = ParseArgs.extractSelsAndArgs(input)

        assert(
          selectors == expectedSelectors,
          args == expectedArgs,
          isMulti == expectedIsMulti
        )
      }

      'empty - check(
        input = Seq.empty,
        expectedSelectors = Seq.empty,
        expectedArgs = Seq.empty,
        expectedIsMulti = false
      )
      'singleSelector - check(
        input = Seq("core.compile"),
        expectedSelectors = Seq("core.compile"),
        expectedArgs = Seq.empty,
        expectedIsMulti = false
      )
      'singleSelectorWithArgs - check(
        input = Seq("application.run", "hello", "world"),
        expectedSelectors = Seq("application.run"),
        expectedArgs = Seq("hello", "world"),
        expectedIsMulti = false
      )
      'singleSelectorWithAllInArgs - check(
        input = Seq("application.run", "hello", "world", "--all"),
        expectedSelectors = Seq("application.run"),
        expectedArgs = Seq("hello", "world", "--all"),
        expectedIsMulti = false
      )
      'multiSelectors - check(
        input = Seq("--all", "core.jar", "core.docsJar", "core.sourcesJar"),
        expectedSelectors = Seq("core.jar", "core.docsJar", "core.sourcesJar"),
        expectedArgs = Seq.empty,
        expectedIsMulti = true
      )
      'multiSelectorsSeq - check(
        input = Seq("--seq", "core.jar", "core.docsJar", "core.sourcesJar"),
        expectedSelectors = Seq("core.jar", "core.docsJar", "core.sourcesJar"),
        expectedArgs = Seq.empty,
        expectedIsMulti = true
      )
      'multiSelectorsWithArgs - check(
        input = Seq(
          "--all",
          "core.compile",
          "application.runMain",
          "--",
          "Main",
          "hello",
          "world"
        ),
        expectedSelectors = Seq("core.compile", "application.runMain"),
        expectedArgs = Seq("Main", "hello", "world"),
        expectedIsMulti = true
      )
      'multiSelectorsWithArgsWithAllInArgs - check(
        input = Seq(
          "--all",
          "core.compile",
          "application.runMain",
          "--",
          "Main",
          "--all",
          "world"
        ),
        expectedSelectors = Seq("core.compile", "application.runMain"),
        expectedArgs = Seq("Main", "--all", "world"),
        expectedIsMulti = true
      )
    }
    'expandBraces - {
      def check(input: String, expectedExpansion: List[String]) = {
        val Right(expanded) = ParseArgs.expandBraces(input)

        assert(expanded == expectedExpansion)
      }

      'expandLeft - check(
        "{application,core}.compile",
        List("application.compile", "core.compile")
      )
      'expandRight - check(
        "application.{jar,docsJar,sourcesJar}",
        List("application.jar", "application.docsJar", "application.sourcesJar")
      )
      'expandBoth - check(
        "{core,application}.{jar,docsJar}",
        List(
          "core.jar",
          "core.docsJar",
          "application.jar",
          "application.docsJar"
        )
      )
      'expandNested - {
        check(
          "{hello,world.{cow,moo}}",
          List("hello", "world.cow", "world.moo")
        )
        check("{a,b{c,d}}", List("a", "bc", "bd"))
        check("{a,b,{c,d}}", List("a", "b", "c", "d"))
        check("{a,b{c,d{e,f}}}", List("a", "bc", "bde", "bdf"))
        check("{a{b,c},d}", List("ab", "ac", "d"))
        check("{a,{b,c}d}", List("a", "bd", "cd"))
        check("{a{b,c},d{e,f}}", List("ab", "ac", "de", "df"))
        check("{a,b{c,d},e{f,g}}", List("a", "bc", "bd", "ef", "eg"))
      }
      'expandMixed - check("{a,b}.{c}.{}.e", List("a.{c}.{}.e", "b.{c}.{}.e"))
      'malformed - {
        val malformed = Seq("core.{compile", "core.{compile,test]")

        malformed.foreach { m =>
          val Left(error) = ParseArgs.expandBraces(m)
          assert(error.contains("Parsing exception"))
        }
      }
      'dontExpand - {
        check("core.compile", List("core.compile"))
        check("{}.compile", List("{}.compile"))
        check("{core}.compile", List("{core}.compile"))
      }
      'keepUnknownSymbols - {
        check("{a,b}.e<>", List("a.e<>", "b.e<>"))
        check("a[99]&&", List("a[99]&&"))
        check(
          "{a,b}.<%%>.{c,d}",
          List("a.<%%>.c", "a.<%%>.d", "b.<%%>.c", "b.<%%>.d")
        )
      }
    }

    'apply - {
      def check(input: Seq[String],
                expectedSelectors: List[List[Segment]],
                expectedArgs: Seq[String]) = {
        val Right((selectors, args)) = ParseArgs(input)

        assert(selectors == expectedSelectors, args == expectedArgs)
      }

      'rejectEmpty {
        assert(ParseArgs(Seq.empty) == Left("Selector cannot be empty"))
      }
      'singleSelector - check(
        input = Seq("core.compile"),
        expectedSelectors = List(List(Label("core"), Label("compile"))),
        expectedArgs = Seq.empty
      )
      'singleSelectorWithArgs - check(
        input = Seq("application.run", "hello", "world"),
        expectedSelectors = List(List(Label("application"), Label("run"))),
        expectedArgs = Seq("hello", "world")
      )
      'singleSelectorWithCross - check(
        input = Seq("bridges[2.12.4,jvm].compile"),
        expectedSelectors = List(
          List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("compile"))
        ),
        expectedArgs = Seq.empty
      )
      'multiSelectorsBraceExpansion - check(
        input = Seq("--all", "{core,application}.compile"),
        expectedSelectors = List(
          List(Label("core"), Label("compile")),
          List(Label("application"), Label("compile"))
        ),
        expectedArgs = Seq.empty
      )
      'multiSelectorsBraceExpansionWithArgs - check(
        input = Seq("--all", "{core,application}.run", "--", "hello", "world"),
        expectedSelectors = List(
          List(Label("core"), Label("run")),
          List(Label("application"), Label("run"))
        ),
        expectedArgs = Seq("hello", "world")
      )
      'multiSelectorsBraceExpansionWithCross - check(
        input = Seq("--all", "bridges[2.12.4,jvm].{test,jar}"),
        expectedSelectors = List(
          List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("test")),
          List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("jar"))
        ),
        expectedArgs = Seq.empty
      )
      'multiSelectorsBraceExpansionInsideCross - check(
        input = Seq("--all", "bridges[{2.11.11,2.11.8}].jar"),
        expectedSelectors = List(
          List(Label("bridges"), Cross(Seq("2.11.11")), Label("jar")),
          List(Label("bridges"), Cross(Seq("2.11.8")), Label("jar"))
        ),
        expectedArgs = Seq.empty
      )
      'multiSelectorsBraceExpansionWithoutAll - {
        assert(
          ParseArgs(Seq("{core,application}.compile")) == Left(
            "Please use --all flag to run multiple tasks"
          )
        )
      }
      'multiSelectorsWithoutAllAsSingle - check( // this is how it works when we pass multiple tasks without --all flag
        input = Seq("core.compile", "application.compile"),
        expectedSelectors = List(List(Label("core"), Label("compile"))),
        expectedArgs = Seq("application.compile")
      )
    }
  }

}
