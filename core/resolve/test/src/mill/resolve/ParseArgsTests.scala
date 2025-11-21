package mill.resolve

import mill.api.Result
import mill.api.{Segment, Segments, SelectMode}
import mill.api.Segment.{Cross, Label}
import mill.resolve.ParseArgs.TaskSeparator
import utest.*

object ParseArgsTests extends TestSuite {

  val tests = Tests {
    test("extractSelsAndArgs") {
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

      test("empty") - check(
        input = Seq.empty,
        expectedSelectors = Seq.empty,
        expectedArgs = Seq.empty,
        multiSelect = false
      )
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
      test("multiSelectorsWithArgsWithAllInArgs") - check(
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

    test("apply(multiselect)") {
      def check(
          input: Seq[String],
          expectedSelectors: List[(Option[List[Segment]], List[Segment])],
          expectedArgs: Seq[String],
          multiSelect: Boolean
      ) = {
        val Result.Success((selectors0, args)) :: _ =
          ParseArgs(input, if (multiSelect) SelectMode.Multi else SelectMode.Separated): @unchecked

        val selectors = selectors0.map {
          case (Some(v1), Some(v2)) => (Some(v1.value), v2.value)
          case (None, Some(v2)) => (None, v2.value)
          case other @ (_, None) => throw Exception(s"Unexpected: $other")
        }
        assert(
          selectors == expectedSelectors,
          args == expectedArgs
        )
      }

      test("rejectEmpty") {
        val parsed = ParseArgs(Seq.empty, selectMode = SelectMode.Separated)
        assert(
          parsed == Seq(Result.Failure(
            "Task selector must not be empty. Try `mill resolve _` to see what's available."
          ))
        )
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
          Some(List(Label("foo.bar/"))) -> List(Label("core"), Label("compile"))
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
        input = Seq("{core,application}.run", ParseArgs.MultiArgsSeparator, "hello", "world"),
        expectedSelectors = List(
          None -> List(Label("core"), Label("run")),
          None -> List(Label("application"), Label("run"))
        ),
        expectedArgs = Seq("hello", "world"),
        multiSelect = true
      )
      test("multiSelectorsBraceWithMissingArgsSeparator") - check(
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
      test("multiSelectorsBraceExpansionWithoutAll") {
        val res = ParseArgs(Seq("{core,application}.compile"), SelectMode.Separated)
        val expected = List(
          Result.Success(
            (
              List(
                None -> Some(Segments(Seq(Label("core"), Label("compile")))),
                None -> Some(Segments(Seq(Label("application"), Label("compile"))))
              ),
              Nil
            )
          )
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

    test("apply(SelectMode.Separated)") {
      val selectMode = SelectMode.Separated
      def parsed(args: String*) = ParseArgs(args, selectMode)
      test("rejectEmpty") {
        val msg = "Task selector must not be empty. Try `mill resolve _` to see what's available."
        assert(parsed("") == List(Result.Failure(msg)))
        assert(parsed() == List(Result.Failure(msg)))
      }
      def check(
          input: Seq[String],
          expectedSelectorArgPairs: Seq[(Seq[(Option[Seq[Segment]], Seq[Segment])], Seq[String])]
      ) = {
        val actual = (ParseArgs(input, selectMode): @unchecked).map {
          case Result.Success((selectors0, args)) =>
            val selectors = selectors0.map {
              case (Some(v1), Some(v2)) => (Some(v1.value), v2.value)
              case (None, Some(v2)) => (None, v2.value)
              case other @ (_, None) => throw Exception(s"Unexpected: $other")
            }
            (selectors, args)
        }
        assert(
          actual == expectedSelectorArgPairs
        )
      }

      test("singleTopLevelTask") {
        check(
          Seq("compile"),
          Seq(
            Seq(
              None -> Seq(Label("compile"))
            ) -> Seq.empty
          )
        )
      }
      test("singleTask") {
        check(
          Seq("core.compile"),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("compile"))
            ) -> Seq.empty
          )
        )
      }
      test("multiTasks") {
        check(
          Seq("core.compile", ParseArgs.TaskSeparator, "app.compile"),
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
      test("multiTasksSupportMaskingSeparator") {
        check(
          Seq(
            "core.run",
            """\""" + ParseArgs.TaskSeparator,
            "arg2",
            "+",
            "run",
            """\\""" + ParseArgs.TaskSeparator,
            """\\\""" + ParseArgs.TaskSeparator,
            """x\\""" + ParseArgs.TaskSeparator
          ),
          Seq(
            Seq(
              None -> Seq(Label("core"), Label("run"))
            ) -> Seq(ParseArgs.TaskSeparator, "arg2"),
            Seq(
              None -> Seq(Label("run"))
            ) -> Seq(
              """\""" + TaskSeparator,
              """\\""" + TaskSeparator,
              """x\\""" + TaskSeparator
            )
          )
        )
      }
      test("singleTaskWithArgs") {
        check(
          Seq("core.run", "arg1", "arg2"),
          Seq(
            Seq(
              None -> List(Label("core"), Label("run"))
            ) -> Seq("arg1", "arg2")
          )
        )
      }
      test("multiTasksWithArgs") {
        check(
          Seq("core.run", "arg1", "arg2", ParseArgs.TaskSeparator, "core.runMain", "my.main"),
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
      test("multiTasksWithArgsAndBrace") {
        check(
          Seq(
            "{core,app,test._}.run",
            "arg1",
            "arg2",
            ParseArgs.TaskSeparator,
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
