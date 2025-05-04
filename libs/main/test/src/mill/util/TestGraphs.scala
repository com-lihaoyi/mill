package mill.util
import mainargs.arg
import mill.testkit.TestBaseModule
import mill.define.{Command, Cross, Discover, TaskModule}
import mill.{Module, Task}

/**
 * Example dependency graphs for us to use in our test suite.
 */

object TestGraphs {
  object singleton extends TestBaseModule {
    def single = Task { 123 }
    lazy val millDiscover = Discover[this.type]
  }
  object bactickIdentifiers extends TestBaseModule {
    def `up-target` = Task { 1 }
    def `a-down-target` = Task { `up-target`() + 2 }
    def `invisible&` = Task { 3 }

    object `nested-module` extends Module {
      def `nested-target` = Task { 4 }
    }

    lazy val millDiscover = Discover[this.type]
  }

  // up---down
  object pair extends TestBaseModule {
    def up = Task { 1 }
    def down = Task { up() + 10 }
    lazy val millDiscover = Discover[this.type]
  }

  // up---o---down
  object anonTriple extends TestBaseModule {
    def up = Task { 1 }
    def anon = Task.Anon { up() + 10 }
    def down = Task { anon() + 100 }
    lazy val millDiscover = Discover[this.type]
  }

  //   left
  //   /   \
  // up    down
  //   \   /
  //   right
  object diamond extends TestBaseModule {
    def up = Task { 1 }
    def left = Task { up() + 10 }
    def right = Task { up() + 100 }
    def down = Task { left() + right() + 1000 }
    lazy val millDiscover = Discover[this.type]
  }

  //    o
  //   / \
  // up   down
  //   \ /
  //    o
  object anonDiamond extends TestBaseModule {
    def up = Task { 1 }
    val left = Task.Anon { up() + 10 }
    val right = Task.Anon { up() + 100 }
    def down = Task { left() + right() + 1000 }
    lazy val millDiscover = Discover[this.type]
  }
  //        _ left _
  //       /        \
  //  task1 -------- right
  //               _/
  // change - task2
  object separateGroups extends TestBaseModule {
    val task1 = Task.Anon { 1 }
    def left = Task { task1() + 10 }
    val change = Task.Anon { 100 }
    val task2 = Task.Anon { change() + 1000 }
    def right = Task { task1() + task2() + left() + 10000 }
    lazy val millDiscover = Discover[this.type]

  }

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
      override def moduleDir = super.moduleDir / crossValue
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
      override def moduleDir = super.moduleDir / crossValue.toString
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
