package mill.javalib.internal

import utest.*

object ModuleUtilsTests extends TestSuite {

  val tests = Tests {

    test("noDependencies") {
      val res = ModuleUtils.recursive("test", "a", (_: String) => Seq.empty)
      assert(res == Seq.empty)
    }

    test("singleLevel") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b", "c")
            case _ => Seq.empty
          }
      )
      assert(res == Seq("c", "b"))
    }

    test("multiLevel") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b", "c")
            case "b" => Seq("d")
            case "c" => Seq("d")
            case _ => Seq.empty
          }
      )
      assert(res == Seq("c", "d", "b"))
    }

    test("deepChain") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b")
            case "b" => Seq("c")
            case "c" => Seq("d")
            case "d" => Seq("e")
            case _ => Seq.empty
          }
      )
      assert(res == Seq("e", "d", "c", "b"))
    }

    test("selfCycle") {
      val exception = assertThrows[mill.api.MillException] {
        ModuleUtils.recursive(
          "test",
          "a",
          s =>
            s match {
              case "a" => Seq("a")
              case _ => Seq.empty
            }
        )
      }
      assert(exception.getMessage.contains("test: cycle detected: a -> a"))
    }

    test("directCycle") {
      val exception = assertThrows[mill.api.MillException] {
        ModuleUtils.recursive(
          "test",
          "a",
          s =>
            s match {
              case "a" => Seq("b")
              case "b" => Seq("a")
              case _ => Seq.empty
            }
        )
      }
      assert(exception.getMessage.contains("test: cycle detected: a -> b -> a"))
    }

    test("indirectCycle") {
      val exception = assertThrows[mill.api.MillException] {
        ModuleUtils.recursive(
          "test",
          "a",
          s =>
            s match {
              case "a" => Seq("b")
              case "b" => Seq("c")
              case "c" => Seq("d")
              case "d" => Seq("b")
              case _ => Seq.empty
            }
        )
      }
      assert(exception.getMessage.contains("test: cycle detected: b -> c -> d -> b"))
    }

    test("cycleNotFromStart") {
      val exception = assertThrows[mill.api.MillException] {
        ModuleUtils.recursive(
          "test",
          "a",
          s =>
            s match {
              case "a" => Seq("b")
              case "b" => Seq("c")
              case "c" => Seq("b")
              case _ => Seq.empty
            }
        )
      }
      assert(exception.getMessage.contains("test: cycle detected: b -> c -> b"))
    }

    test("diamondNoDuplicates") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b", "c")
            case "b" => Seq("d")
            case "c" => Seq("d")
            case _ => Seq.empty
          }
      )
      assert(res == Seq("c", "d", "b"))
    }

    test("wideTree") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b", "c", "d", "e", "f")
            case _ => Seq.empty
          }
      )
      assert(res == Seq("f", "e", "d", "c", "b"))
    }

    test("complexDAG") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b", "c")
            case "b" => Seq("d", "e")
            case "c" => Seq("e", "f")
            case "d" => Seq("f")
            case "e" => Seq("f")
            case _ => Seq.empty
          }
      )
      assert(res == Seq("c", "e", "f", "d", "b"))
    }

    test("customNameUsedInErrorMessage") {
      val exception = assertThrows[mill.api.MillException] {
        ModuleUtils.recursive(
          "myCustomName",
          "a",
          s =>
            s match {
              case "a" => Seq("b")
              case "b" => Seq("a")
              case _ => Seq.empty
            }
        )
      }
      assert(exception.getMessage.contains("myCustomName: cycle detected"))
    }

    test("doesNotIncludeStartNode") {
      val res = ModuleUtils.recursive(
        "test",
        "a",
        s =>
          s match {
            case "a" => Seq("b")
            case "b" => Seq("c")
            case _ => Seq.empty
          }
      )
      assert(!res.contains("a"))
      assert(res == Seq("c", "b"))
    }
  }
}
