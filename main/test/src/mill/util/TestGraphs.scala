package mill.util
import TestUtil.test
import mainargs.arg
import mill.testkit.TestBaseModule
import mill.define.{Command, Cross, Discover, DynamicModule, ModuleRef, TaskModule}
import mill.{Module, T, Task}

/**
 * Example dependency graphs for us to use in our test suite.
 *
 * The graphs using `test()` live in the `class` and need to be instantiated
 * every time you use them, because they are mutable (you can poke at the
 * `test`'s `counter`/`failure`/`exception` fields to test various graph
 * evaluation scenarios).
 *
 * The immutable graphs, used for testing discovery & target resolution,
 * live in the companion object.
 */
class TestGraphs() {
  // single
  object singleton extends TestBaseModule {
    val single = test()
    lazy val millDiscover = Discover[this.type]
  }

  object bactickIdentifiers extends TestBaseModule {
    val `up-target` = test()
    val `a-down-target` = test(`up-target`)
    val `invisible&` = test()
    object `nested-module` extends Module {
      val `nested-target` = test()
    }
    lazy val millDiscover = Discover[this.type]
  }

  // up---down
  object pair extends TestBaseModule {
    val up = test()
    val down = test(up)
    lazy val millDiscover = Discover[this.type]
  }

  // up---o---down
  object anonTriple extends TestBaseModule {
    val up = test()
    val down = test(test.anon(up))
    lazy val millDiscover = Discover[this.type]
  }

  //   left
  //   /   \
  // up    down
  //   \   /
  //   right
  object diamond extends TestBaseModule {
    val up = test()
    val left = test(up)
    val right = test(up)
    val down = test(left, right)
    lazy val millDiscover = Discover[this.type]
  }

  //    o
  //   / \
  // up   down
  //   \ /
  //    o
  object anonDiamond extends TestBaseModule {
    val up = test()
    val down = test(test.anon(up), test.anon(up))
    lazy val millDiscover = Discover[this.type]
  }

  //          o   g-----o
  //           \   \     \
  // o          o   h-----I---o
  //  \        / \ / \   / \   \
  //   A---c--o   E   o-o   \   \
  //  / \ / \    / \         o---J
  // o   d   o--o   o       /   /
  //      \ /        \     /   /
  //       o          o---F---o
  //      /          /
  //  o--B          o
  object bigSingleTerminal extends TestBaseModule {
    val a = test(test.anon(), test.anon())
    val b = test(test.anon())
    val e = {
      val c = test.anon(a)
      val d = test.anon(a)
      test(
        test.anon(test.anon(), test.anon(c)),
        test.anon(test.anon(c, test.anon(d, b)))
      )
    }
    val f = test(test.anon(test.anon(), test.anon(e)))

    val i = {
      val g = test.anon()
      val h = test.anon(g, e)
      test(test.anon(g), test.anon(test.anon(h)))
    }
    val j = test(test.anon(i), test.anon(i, f), test.anon(f))
    lazy val millDiscover = Discover[this.type]
  }
  //        _ left _
  //       /        \
  //  task1 -------- right
  //               _/
  // change - task2
  object separateGroups extends TestBaseModule {
    val task1 = Task.Anon { 1 }
    def left = Task { task1() }
    val change = test()
    val task2 = Task.Anon { change() }
    def right = Task { task1() + task2() + left() + 1 }
    lazy val millDiscover = Discover[this.type]

  }
}

object TestGraphs {
  //      _ left _
  //     /        \
  // task -------- right
  object triangleTask extends TestBaseModule {
    val task = Task.Anon { 1 }
    def left = Task { task() }
    def right = Task { task() + left() + 1 }
    lazy val millDiscover = Discover[this.type]
  }

  //      _ left
  //     /
  // task -------- right
  object multiTerminalGroup extends TestBaseModule {
    val task = Task.Anon { 1 }
    def left = Task { task() }
    def right = Task { task() }
    lazy val millDiscover = Discover[this.type]
  }

  //       _ left _____________
  //      /        \           \
  // task1 -------- right ----- task2
  object multiTerminalBoundary extends TestBaseModule {
    val task1 = Task.Anon { 1 }
    def left = Task { task1() }
    def right = Task { task1() + left() + 1 }
    val task2 = Task.Anon { left() + right() }
    lazy val millDiscover = Discover[this.type]
  }

  trait CanNest extends Module {
    def single = Task { 1 }
    def invisible: Any = Task { 2 }
    def invisible2: mill.define.Task[Int] = Task { 3 }
    def invisible3: mill.define.Task[?] = Task { 4 }
  }

  object nestedModule extends TestBaseModule {
    def single = Task { 5 }
    def invisible: Any = Task { 6 }
    object nested extends Module {
      def single = Task { 7 }
      def invisible: Any = Task { 8 }

    }
    object classInstance extends CanNest

    lazy val millDiscover = Discover[this.type]
  }

  trait TraitWithModule extends Module { outer =>
    object TraitModule extends Module {
      def testFrameworks = Task { Seq("mill.UTestFramework") }
      def test() = Task.Command { () /*do nothing*/ }
    }
  }

  // Make sure nested objects inherited from traits work
  object TraitWithModuleObject extends TestBaseModule with TraitWithModule {
    lazy val millDiscover = Discover[this.type]
  }

  object singleCross extends TestBaseModule {
    object cross extends mill.Cross[Cross]("210", "211", "212")
    trait Cross extends Cross.Module[String] {
      def suffix = Task { crossValue }
    }

    object cross2 extends mill.Cross[Cross2]("210", "211", "212")
    trait Cross2 extends Cross.Module[String] {
      override def millSourcePath = super.millSourcePath / crossValue
      def suffix = Task { crossValue }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object nonStringCross extends TestBaseModule {
    object cross extends mill.Cross[Cross](210, 211, 212)
    trait Cross extends Cross.Module[Int] {
      def suffix = Task { crossValue }
    }

    object cross2 extends mill.Cross[Cross2](210L, 211L, 212L)
    trait Cross2 extends Cross.Module[Long] {
      override def millSourcePath = super.millSourcePath / crossValue.toString
      def suffix = Task { crossValue }
    }

    lazy val millDiscover = Discover[this.type]
  }

  object crossResolved extends TestBaseModule {
    trait MyModule extends Cross.Module[String] {
      implicit object resolver extends mill.define.Cross.Resolver[MyModule] {
        def resolve[V <: MyModule](c: Cross[V]): V = c.valuesToModules(List(crossValue))
      }
    }

    object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
    trait FooModule extends MyModule {
      def suffix = Task { crossValue }
    }

    object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
    trait BarModule extends MyModule {
      def longSuffix = Task { "_" + foo().suffix() }
    }
    lazy val millDiscover = Discover[this.type]
  }
  object doubleCross extends TestBaseModule {
    val crossMatrix = for {
      scalaVersion <- Seq("210", "211", "212")
      platform <- Seq("jvm", "js", "native")
      if !(platform == "native" && scalaVersion != "212")
    } yield (scalaVersion, platform)
    object cross extends mill.Cross[Cross](crossMatrix)
    trait Cross extends Cross.Module2[String, String] {
      val (scalaVersion, platform) = (crossValue, crossValue2)
      def suffix = Task { scalaVersion + "_" + platform }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object crossExtension extends TestBaseModule {
    object myCross extends Cross[MyCrossModule]("a", "b")
    trait MyCrossModule extends Cross.Module[String] {
      def param1 = Task { "Param Value: " + crossValue }
    }

    object myCrossExtended extends Cross[MyCrossModuleExtended](("a", 1), ("b", 2))
    trait MyCrossModuleExtended extends MyCrossModule with Cross.Module2[String, Int] {
      def param2 = Task { "Param Value: " + crossValue2 }
    }

    object myCrossExtendedAgain
        extends Cross[MyCrossModuleExtendedAgain](("a", 1, true), ("b", 2, false))
    trait MyCrossModuleExtendedAgain extends MyCrossModuleExtended
        with Cross.Module3[String, Int, Boolean] {
      def param3 = Task { "Param Value: " + crossValue3 }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object innerCrossModule extends TestBaseModule {
    object myCross extends Cross[MyCrossModule]("a", "b")
    trait MyCrossModule extends Cross.Module[String] {
      object foo extends CrossValue {
        def bar = Task { "foo " + crossValue }
      }

      object baz extends CrossValue {
        def bar = Task { "baz " + crossValue }
      }
    }

    object myCross2 extends Cross[MyCrossModule2](("a", 1), ("b", 2))
    trait MyCrossModule2 extends Cross.Module2[String, Int] {
      object foo extends InnerCrossModule2 {
        def bar = Task { "foo " + crossValue }
        def qux = Task { "foo " + crossValue2 }
      }
      object baz extends InnerCrossModule2 {
        def bar = Task { "baz " + crossValue }
        def qux = Task { "baz " + crossValue2 }
      }
    }

    object myCross3 extends Cross[MyCrossModule3](("a", 1, true), ("b", 2, false))
    trait MyCrossModule3 extends Cross.Module3[String, Int, Boolean] {
      object foo extends InnerCrossModule3 {
        def bar = Task { "foo " + crossValue }
        def qux = Task { "foo " + crossValue2 }
        def lol = Task { "foo " + crossValue3 }
      }
      object baz extends InnerCrossModule3 {
        def bar = Task { "baz " + crossValue }
        def qux = Task { "baz " + crossValue2 }
        def lol = Task { "baz " + crossValue3 }
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object nestedCrosses extends TestBaseModule {
    object cross extends mill.Cross[Cross]("210", "211", "212") {
      override def defaultCrossSegments: Seq[String] = Seq("212")
    }
    trait Cross extends Cross.Module[String] {
      val scalaVersion = crossValue
      object cross2 extends mill.Cross[Cross]("jvm", "js", "native")
      trait Cross extends Cross.Module[String] {
        val platform = crossValue
        def suffix = Task { scalaVersion + "_" + platform }
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object nestedTaskCrosses extends TestBaseModule {
    // this is somehow necessary to let Discover see our inner (default) commands
    // I expected, that the identical inherited `millDiscover` is enough, but it isn't
    lazy val millDiscover = Discover[this.type]
    object cross1 extends mill.Cross[Cross1]("210", "211", "212")
    trait Cross1 extends mill.Cross.Module[String] {
      def scalaVersion = crossValue

      object cross2 extends mill.Cross[Cross2]("jvm", "js", "native")
      trait Cross2 extends mill.Cross.Module[String] with TaskModule {
        def platform = crossValue
        override def defaultCommandName(): String = "suffixCmd"
        def suffixCmd(@arg(positional = true) suffix: String = "default"): Command[String] =
          Task.Command {
            scalaVersion + "_" + platform + "_" + suffix
          }
      }

    }
  }
}
