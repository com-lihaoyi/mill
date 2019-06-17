package mill.util

import mill.define.{Segment, Segments}
import mill.define.Segment.{Cross, Label}
import utest._

object ParseArgsTest extends TestSuite {

  val tests = Tests {
    test("extractSelsAndArgs"){
      def check(input: Seq[String],
                expectedSelectors: Seq[String],
                expectedArgs: Seq[String],
                multiSelect: Boolean) = {
        val (selectors, args) = ParseArgs.extractSelsAndArgs(input, multiSelect)

        assert(
          selectors == expectedSelectors,
          args == expectedArgs
        )
      }

      test("empty") - check(input = Seq.empty,
                     expectedSelectors = Seq.empty,
                     expectedArgs = Seq.empty,
                     multiSelect = false)
      test("singleSelector") - check(
        input = Seq("core.compile"),
        expectedSelectors = Seq("core.compile"),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      test("singleSelectorWithArgs") - check(
        input = Seq("application.run", "hello", "world"),
        expectedSelectors = Seq("application.run"),
        expectedArgs = Seq("hello", "world"),
        multiSelect = false
      )
      test("singleSelectorWithAllInArgs") - check(
        input = Seq("application.run", "hello", "world", "--all"),
        expectedSelectors = Seq("application.run"),
        expectedArgs = Seq("hello", "world", "--all"),
        multiSelect = false
      )
      test("multiSelectors") - check(
        input = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedSelectors = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      test("multiSelectorsSeq") - check(
        input = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedSelectors = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      test("multiSelectorsWithArgs") - check(
        input = Seq("core.compile",
                    "application.runMain",
                    "--",
                    "Main",
                    "hello",
                    "world"),
        expectedSelectors = Seq("core.compile", "application.runMain"),
        expectedArgs = Seq("Main", "hello", "world"),
        multiSelect = true
      )
      test("multiSelectorsWithArgsWithAllInArgs") - check(
        input = Seq("core.compile",
                    "application.runMain",
                    "--",
                    "Main",
                    "--all",
                    "world"),
        expectedSelectors = Seq("core.compile", "application.runMain"),
        expectedArgs = Seq("Main", "--all", "world"),
        multiSelect = true
      )
    }
    test("expandBraces"){
      def check(input: String, expectedExpansion: List[String]) = {
        val Right(expanded) = ParseArgs.expandBraces(input)

        assert(expanded == expectedExpansion)
      }

      test("expandLeft") - check(
        "{application,core}.compile",
        List("application.compile", "core.compile")
      )
      test("expandRight") - check(
        "application.{jar,docJar,sourcesJar}",
        List("application.jar", "application.docJar", "application.sourcesJar")
      )
      test("expandBoth") - check(
        "{core,application}.{jar,docJar}",
        List(
          "core.jar",
          "core.docJar",
          "application.jar",
          "application.docJar"
        )
      )
      test("expandNested"){
        check("{hello,world.{cow,moo}}",
              List("hello", "world.cow", "world.moo"))
        check("{a,b{c,d}}", List("a", "bc", "bd"))
        check("{a,b,{c,d}}", List("a", "b", "c", "d"))
        check("{a,b{c,d{e,f}}}", List("a", "bc", "bde", "bdf"))
        check("{a{b,c},d}", List("ab", "ac", "d"))
        check("{a,{b,c}d}", List("a", "bd", "cd"))
        check("{a{b,c},d{e,f}}", List("ab", "ac", "de", "df"))
        check("{a,b{c,d},e{f,g}}", List("a", "bc", "bd", "ef", "eg"))
      }
      test("expandMixed") - check(
        "{a,b}.{c}.{}.e",
        List("a.{c}.{}.e", "b.{c}.{}.e")
      )
      test("malformed"){
        val malformed = Seq("core.{compile", "core.{compile,test]")

        malformed.foreach { m =>
          val Left(error) = ParseArgs.expandBraces(m)
          assert(error.contains("Parsing exception"))
        }
      }
      test("dontExpand"){
        check("core.compile", List("core.compile"))
        check("{}.compile", List("{}.compile"))
        check("{core}.compile", List("{core}.compile"))
      }
      test("keepUnknownSymbols"){
        check("{a,b}.e<>", List("a.e<>", "b.e<>"))
        check("a[99]&&", List("a[99]&&"))
        check(
          "{a,b}.<%%>.{c,d}",
          List("a.<%%>.c", "a.<%%>.d", "b.<%%>.c", "b.<%%>.d")
        )
      }
    }

    test("apply"){
      def check(input: Seq[String],
                expectedSelectors: List[(Option[List[Segment]], List[Segment])],
                expectedArgs: Seq[String],
                multiSelect: Boolean) = {
        val Right((selectors0, args)) = ParseArgs(input, multiSelect)

        val selectors = selectors0.map{
          case (Some(v1), v2) => (Some(v1.value), v2.value)
          case (None, v2) => (None, v2.value)
        }
        assert(
          selectors == expectedSelectors,
          args == expectedArgs
        )
      }

      test("rejectEmpty"){
        assert(ParseArgs(Seq.empty, multiSelect = false) == Left("Selector cannot be empty"))
      }
      test("singleSelector") - check(
        input = Seq("core.compile"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      test("externalSelector") - check(
        input = Seq("foo.bar/core.compile"),
        expectedSelectors = List(
          Some(List(Label("foo"), Label("bar"))) -> List(Label("core"), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      test("singleSelectorWithArgs") - check(
        input = Seq("application.run", "hello", "world"),
        expectedSelectors = List(
          None -> List(Label("application"), Label("run"))
        ),
        expectedArgs = Seq("hello", "world"),
        multiSelect = false
      )
      test("singleSelectorWithCross") - check(
        input = Seq("bridges[2.12.4,jvm].compile"),
        expectedSelectors = List(
          None -> List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      test("multiSelectorsBraceExpansion") - check(
        input = Seq("{core,application}.compile"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("compile")),
          None -> List(Label("application"), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      test("multiSelectorsBraceExpansionWithArgs") - check(
        input = Seq("{core,application}.run", "--", "hello", "world"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("run")),
          None -> List(Label("application"), Label("run"))
        ),
        expectedArgs = Seq("hello", "world"),
        multiSelect = true
      )
      test("multiSelectorsBraceExpansionWithCross") - check(
        input = Seq("bridges[2.12.4,jvm].{test,jar}"),
        expectedSelectors = List(
          None -> List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("test")),
          None -> List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("jar"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      test("multiSelectorsBraceExpansionInsideCross") - check(
        input = Seq("bridges[{2.11.11,2.11.8,2.13.0-M3}].jar"),
        expectedSelectors = List(
          None -> List(Label("bridges"), Cross(Seq("2.11.11")), Label("jar")),
          None -> List(Label("bridges"), Cross(Seq("2.11.8")), Label("jar")),
          None -> List(Label("bridges"), Cross(Seq("2.13.0-M3")), Label("jar"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      test("multiSelectorsBraceExpansionWithoutAll"){
        val res = ParseArgs(Seq("{core,application}.compile"), multiSelect = false)
        val expected = Right(
          List(
            None -> Segments(Label("core"), Label("compile")),
            None -> Segments(Label("application"), Label("compile"))
          ),
          Nil
        )
        assert(res == expected)
      }
      test("multiSelectorsWithoutAllAsSingle") - check(
        // this is how it works when we pass multiple tasks without --all flag
        input = Seq("core.compile", "application.compile"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("compile"))
        ),
        expectedArgs = Seq("application.compile"),
        multiSelect = false
      )
    }
  }

}
