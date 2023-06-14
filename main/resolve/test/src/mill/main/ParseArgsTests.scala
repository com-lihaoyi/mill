package mill.resolve

import mill.define.{Segment, Segments}
import mill.define.Segment.{Cross, Label}
import mill.resolve.ParseArgs.TargetSeparator
import utest._

object ParseArgsTests extends TestSuite {

  val tests = Tests {
    "extractSelsAndArgs" - {
      def check(
          input: Seq[String],
          expectedSelectors: Seq[String],
          expectedArgs: Seq[String],
          multiSelect: Boolean
      ) = {
        val (selectors, args) = ParseArgs.extractSelsAndArgs(input, multiSelect)

        assert(
          selectors == expectedSelectors,
          args == expectedArgs
        )
      }

      "empty" - check(
        input = Seq.empty,
        expectedSelectors = Seq.empty,
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      "singleSelector" - check(
        input = Seq("core.compile"),
        expectedSelectors = Seq("core.compile"),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      "singleSelectorWithArgs" - check(
        input = Seq("application.run", "hello", "world"),
        expectedSelectors = Seq("application.run"),
        expectedArgs = Seq("hello", "world"),
        multiSelect = false
      )
      "singleSelectorWithAllInArgs" - check(
        input = Seq("application.run", "hello", "world", "--all"),
        expectedSelectors = Seq("application.run"),
        expectedArgs = Seq("hello", "world", "--all"),
        multiSelect = false
      )
      "multiSelectors" - check(
        input = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedSelectors = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      "multiSelectorsSeq" - check(
        input = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedSelectors = Seq("core.jar", "core.docJar", "core.sourcesJar"),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      "multiSelectorsWithArgs" - check(
        input = Seq(
          "core.compile",
          "application.runMain",
          ParseArgs.MultiArgsSeparator,
          "Main",
          "hello",
          "world"
        ),
        expectedSelectors = Seq("core.compile", "application.runMain"),
        expectedArgs = Seq("Main", "hello", "world"),
        multiSelect = true
      )
      "multiSelectorsWithArgsWithAllInArgs" - check(
        input = Seq(
          "core.compile",
          "application.runMain",
          ParseArgs.MultiArgsSeparator,
          "Main",
          "--all",
          "world"
        ),
        expectedSelectors = Seq("core.compile", "application.runMain"),
        expectedArgs = Seq("Main", "--all", "world"),
        multiSelect = true
      )
    }

    "apply(multiselect)" - {
      def check(
          input: Seq[String],
          expectedSelectors: List[(Option[List[Segment]], List[Segment])],
          expectedArgs: Seq[String],
          multiSelect: Boolean
      ) = {
        val Right((selectors0, args) :: _) =
          ParseArgs(input, if (multiSelect) SelectMode.Multi else SelectMode.Separated)

        val selectors = selectors0.map {
          case (Some(v1), v2) => (Some(v1.value), v2.value)
          case (None, v2) => (None, v2.value)
        }
        assert(
          selectors == expectedSelectors,
          args == expectedArgs
        )
      }

      "rejectEmpty" - {
        val parsed = ParseArgs(Seq.empty, selectMode = SelectMode.Separated)
        assert(
          parsed == Left("Selector cannot be empty. Try `mill resolve _` to see what's available.")
        )
      }
      "singleSelector" - check(
        input = Seq("core.compile"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      "externalSelector" - check(
        input = Seq("foo.bar/core.compile"),
        expectedSelectors = List(
          Some(List(Label("foo"), Label("bar"))) -> List(Label("core"), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      "singleSelectorWithArgs" - check(
        input = Seq("application.run", "hello", "world"),
        expectedSelectors = List(
          None -> List(Label("application"), Label("run"))
        ),
        expectedArgs = Seq("hello", "world"),
        multiSelect = false
      )
      "singleSelectorWithCross" - check(
        input = Seq("bridges[2.12.4,jvm].compile"),
        expectedSelectors = List(
          None -> List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = false
      )
      "multiSelectorsBraceExpansion" - check(
        input = Seq("{core,application}.compile"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("compile")),
          None -> List(Label("application"), Label("compile"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      "multiSelectorsBraceExpansionWithArgs" - check(
        input = Seq("{core,application}.run", ParseArgs.MultiArgsSeparator, "hello", "world"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("run")),
          None -> List(Label("application"), Label("run"))
        ),
        expectedArgs = Seq("hello", "world"),
        multiSelect = true
      )
      "multiSelectorsBraceWithMissingArgsSeparator" - check(
        input = Seq("{core,application}.run", "hello", "world"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("run")),
          None -> List(Label("application"), Label("run")),
          None -> List(Label("hello")),
          None -> List(Label("world"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      "multiSelectorsBraceExpansionWithCross" - check(
        input = Seq("bridges[2.12.4,jvm].{test,jar}"),
        expectedSelectors = List(
          None -> List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("test")),
          None -> List(Label("bridges"), Cross(Seq("2.12.4", "jvm")), Label("jar"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      "multiSelectorsBraceExpansionInsideCross" - check(
        input = Seq("bridges[{2.11.11,2.11.8,2.13.0-M3}].jar"),
        expectedSelectors = List(
          None -> List(Label("bridges"), Cross(Seq("2.11.11")), Label("jar")),
          None -> List(Label("bridges"), Cross(Seq("2.11.8")), Label("jar")),
          None -> List(Label("bridges"), Cross(Seq("2.13.0-M3")), Label("jar"))
        ),
        expectedArgs = Seq.empty,
        multiSelect = true
      )
      "multiSelectorsBraceExpansionWithoutAll" - {
        val res = ParseArgs(Seq("{core,application}.compile"), SelectMode.Separated)
        val expected = Right(
          List(
            (
              List(
                None -> Segments(Seq(Label("core"), Label("compile"))),
                None -> Segments(Seq(Label("application"), Label("compile")))
              ),
              Nil
            )
          )
        )
        assert(res == expected)
      }
      "multiSelectorsWithoutAllAsSingle" - check(
        // this is how it works when we pass multiple tasks without --all flag
        input = Seq("core.compile", "application.compile"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("compile"))
        ),
        expectedArgs = Seq("application.compile"),
        multiSelect = false
      )
    }

    test("apply(SelectMode.Separated)") {
      val selectMode = SelectMode.Separated
      def parsed(args: String*) = ParseArgs(args, selectMode)
      test("rejectEmpty") {
        val msg = "Selector cannot be empty. Try `mill resolve _` to see what's available."
        assert(parsed("") == Left(msg))
        assert(parsed() == Left(msg))
      }
      def check(
          input: Seq[String],
          expectedSelectorArgPairs: Seq[(Seq[(Option[Seq[Segment]], Seq[Segment])], Seq[String])]
      ) = {
        val Right(parsed) = ParseArgs(input, selectMode)
        val actual = parsed.map {
          case (selectors0, args) =>
            val selectors = selectors0.map {
              case (Some(v1), v2) => (Some(v1.value), v2.value)
              case (None, v2) => (None, v2.value)
            }
            (selectors, args)
        }
        assert(
          actual == expectedSelectorArgPairs
        )
      }

      test("singleTopLevelTarget") {
        check(
          Seq("compile"),
          Seq(
            Seq(
              None -> Seq(Label("compile"))
            ) -> Seq.empty
          )
        )
      }
      test("singleTarget") {
        check(
          Seq("core.compile"),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("compile"))
            ) -> Seq.empty
          )
        )
      }
      test("multiTargets") {
        check(
          Seq("core.compile", ParseArgs.TargetSeparator, "app.compile"),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("compile"))
            ) -> Seq.empty,
            Seq(
              None -> Seq(Label("app"), Label("compile"))
            ) -> Seq.empty
          )
        )
      }
      test("multiTargetsSupportMaskingSeparator") {
        check(
          Seq(
            "core.run",
            """\""" + ParseArgs.TargetSeparator,
            "arg2",
            "+",
            "run",
            """\\""" + ParseArgs.TargetSeparator,
            """\\\""" + ParseArgs.TargetSeparator,
            """x\\""" + ParseArgs.TargetSeparator
          ),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("run"))
            ) -> Seq(ParseArgs.TargetSeparator, "arg2"),
            Seq(
              None -> Seq(Label("run"))
            ) -> Seq(
              """\""" + TargetSeparator,
              """\\""" + TargetSeparator,
              """x\\""" + TargetSeparator
            )
          )
        )
      }
      test("singleTargetWithArgs") {
        check(
          Seq("core.run", "arg1", "arg2"),
          Seq(
            Seq(
              None -> List(Label("core"), Label("run"))
            ) -> Seq("arg1", "arg2")
          )
        )
      }
      test("multiTargetsWithArgs") {
        check(
          Seq("core.run", "arg1", "arg2", ParseArgs.TargetSeparator, "core.runMain", "my.main"),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("run"))
            ) -> Seq("arg1", "arg2"),
            Seq(
              None -> Seq(Label("core"), Label("runMain"))
            ) -> Seq("my.main")
          )
        )
      }
      test("multiTargetsWithArgsAndBrace") {
        check(
          Seq(
            "{core,app,test._}.run",
            "arg1",
            "arg2",
            ParseArgs.TargetSeparator,
            "core.runMain",
            "my.main"
          ),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("run")),
              None -> Seq(Label("app"), Label("run")),
              None -> Seq(Label("test"), Label("_"), Label("run"))
            ) -> Seq("arg1", "arg2"),
            Seq(
              None -> Seq(Label("core"), Label("runMain"))
            ) -> Seq("my.main")
          )
        )
      }
    }

  }
}
