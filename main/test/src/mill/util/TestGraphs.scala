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
 * evaluation scenarios.
 *
 * The immutable graphs, used for testing discovery & target resolution,
 * live in the companion object.
 */
class TestGraphs() {
  // single
  object singleton extends TestBaseModule {
    val single = test()
  }

  object bactickIdentifiers extends TestBaseModule {
    val `up-target` = test()
    val `a-down-target` = test(`up-target`)
    val `invisible&` = test()
    object `nested-module` extends Module {
      val `nested-target` = test()
    }
  }

  // up---down
  object pair extends TestBaseModule {
    val up = test()
    val down = test(up)
  }

  // up---o---down
  object anonTriple extends TestBaseModule {
    val up = test()
    val down = test(test.anon(up))
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
  }

  //    o
  //   / \
  // up   down
  //   \ /
  //    o
  object anonDiamond extends TestBaseModule {
    val up = test()
    val down = test(test.anon(up), test.anon(up))
  }

  object defCachedDiamond extends TestBaseModule {
    def up = Task { test() }
    def left = Task { test(up) }
    def right = Task { test(up) }
    def down = Task { test(left, right) }
  }

  object borkedCachedDiamond2 extends TestBaseModule {
    def up = test()
    def left = test(up)
    def right = test(up)
    def down = test(left, right)
  }

  object borkedCachedDiamond3 extends TestBaseModule {
    def up = test()
    def left = test(up)
    def right = test(up)
    def down = test(left, right)
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

  }

  object moduleInitError extends TestBaseModule {
    def rootTarget = Task { println("Running rootTarget"); "rootTarget Result" }
    def rootCommand(@arg(positional = true) s: String) =
      Task.Command { println(s"Running rootCommand $s") }

    object foo extends Module {
      def fooTarget = Task { println(s"Running fooTarget"); 123 }
      def fooCommand(@arg(positional = true) s: String) =
        Task.Command { println(s"Running fooCommand $s") }
      throw new Exception("Foo Boom")
    }

    object bar extends Module {
      def barTarget = Task { println(s"Running barTarget"); "barTarget Result" }
      def barCommand(@arg(positional = true) s: String) =
        Task.Command { println(s"Running barCommand $s") }

      object qux extends Module {
        def quxTarget = Task { println(s"Running quxTarget"); "quxTarget Result" }
        def quxCommand(@arg(positional = true) s: String) =
          Task.Command { println(s"Running quxCommand $s") }
        throw new Exception("Qux Boom")
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object moduleDependencyInitError extends TestBaseModule {

    object foo extends Module {
      def fooTarget = Task { println(s"Running fooTarget"); 123 }
      def fooCommand(@arg(positional = true) s: String) =
        Task.Command { println(s"Running fooCommand $s") }
      throw new Exception("Foo Boom")
    }

    object bar extends Module {
      def barTarget = Task {
        println(s"Running barTarget")
        s"${foo.fooTarget()} barTarget Result"
      }
      def barCommand(@arg(positional = true) s: String) = Task.Command {
        foo.fooCommand(s)()
        println(s"Running barCommand $s")
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object crossModuleSimpleInitError extends TestBaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, 4) {
      throw new Exception(s"MyCross Boom")
    }
    trait MyCross extends Cross.Module[Int] {
      def foo = Task { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }
  object crossModulePartialInitError extends TestBaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, 4)
    trait MyCross extends Cross.Module[Int] {
      if (crossValue > 2) throw new Exception(s"MyCross Boom $crossValue")
      def foo = Task { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }
  object crossModuleSelfInitError extends TestBaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, throw new Exception(s"MyCross Boom"))
    trait MyCross extends Cross.Module[Int] {
      def foo = Task { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object crossModuleParentInitError extends TestBaseModule {
    object parent extends Module {
      throw new Exception(s"Parent Boom")
      object myCross extends Cross[MyCross](1, 2, 3, 4)
      trait MyCross extends Cross.Module[Int] {
        def foo = Task { crossValue }
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object cyclicModuleRefInitError extends TestBaseModule {
    import mill.Agg

    // See issue: https://github.com/com-lihaoyi/mill/issues/3715
    trait CommonModule extends TestBaseModule {
      def moduleDeps: Seq[CommonModule] = Seq.empty
      def a = myA
      def b = myB
    }

    object myA extends A
    trait A extends CommonModule
    object myB extends B
    trait B extends CommonModule {
      override def moduleDeps = super.moduleDeps ++ Agg(a)
    }
  }

  // The module names repeat, but it's not actually cyclic and is meant to confuse the cycle detection.
  object nonCyclicModules extends TestBaseModule {
    object A extends TestBaseModule {
      def b = B
    }
    object B extends TestBaseModule {
      object A extends TestBaseModule {
        def b = B
      }
      def a = A

      object B extends TestBaseModule {
        object B extends TestBaseModule {}
        object A extends TestBaseModule {
          def b = B
        }
        def a = A
      }
    }
  }

  // This edge case shouldn't be an error
  object moduleRefWithNonModuleRefChild extends TestBaseModule {
    def aRef = A
    def a = ModuleRef(A)

    object A extends TestBaseModule {}
  }

  object overrideModule extends TestBaseModule {
    trait Base extends Module {
      lazy val inner: BaseInnerModule = new BaseInnerModule {}
      lazy val ignored: ModuleRef[BaseInnerModule] = ModuleRef(new BaseInnerModule {})
      trait BaseInnerModule extends mill.define.Module {
        def baseTarget = Task { 1 }
      }
    }
    object sub extends Base {
      override lazy val inner: SubInnerModule = new SubInnerModule {}
      override lazy val ignored: ModuleRef[SubInnerModule] = ModuleRef(new SubInnerModule {})
      trait SubInnerModule extends BaseInnerModule {
        def subTarget = Task { 2 }
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object dynamicModule extends TestBaseModule {
    object normal extends DynamicModule {
      object inner extends Module {
        def target = Task { 1 }
      }
    }
    object niled extends DynamicModule {
      override def millModuleDirectChildren: Seq[Module] = Nil
      object inner extends Module {
        def target = Task { 1 }
      }
    }

    override lazy val millDiscover = Discover[this.type]
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
  }

  //      _ left
  //     /
  // task -------- right
  object multiTerminalGroup extends TestBaseModule {
    val task = Task.Anon { 1 }
    def left = Task { task() }
    def right = Task { task() }
  }

  //       _ left _____________
  //      /        \           \
  // task1 -------- right ----- task2
  object multiTerminalBoundary extends TestBaseModule {
    val task1 = Task.Anon { 1 }
    def left = Task { task1() }
    def right = Task { task1() + left() + 1 }
    val task2 = Task.Anon { left() + right() }
  }

  trait CanNest extends Module {
    def single = Task { 1 }
    def invisible: Any = Task { 2 }
    def invisible2: mill.define.Task[Int] = Task { 3 }
    def invisible3: mill.define.Task[_] = Task { 4 }
  }
  object nestedModule extends TestBaseModule {
    def single = Task { 5 }
    def invisible: Any = Task { 6 }
    object nested extends Module {
      def single = Task { 7 }
      def invisible: Any = Task { 8 }

    }
    object classInstance extends CanNest

  }
  object doubleNestedModule extends TestBaseModule {
    def single = Task { 5 }
    object nested extends Module {
      def single = Task { 7 }

      object inner extends Module {
        def single = Task { 9 }
      }
    }
  }

  trait BaseModule extends Module {
    def foo = Task { Seq("base") }
    def cmd(i: Int) = Task.Command { Seq("base" + i) }
  }

  object canOverrideSuper extends TestBaseModule with BaseModule {
    override def foo = Task { super.foo() ++ Seq("object") }
    override def cmd(i: Int) = Task.Command { super.cmd(i)() ++ Seq("object" + i) }
    override lazy val millDiscover: Discover = Discover[this.type]
  }

  trait TraitWithModule extends Module { outer =>
    object TraitModule extends Module {
      def testFrameworks = Task { Seq("mill.UTestFramework") }
      def test() = Task.Command { () /*donothing*/ }
    }
  }

  // Make sure nested objects inherited from traits work
  object TraitWithModuleObject extends TestBaseModule with TraitWithModule {
    override lazy val millDiscover: Discover = Discover[this.type]
  }

  object nullTasks extends TestBaseModule {
    val nullString: String = null
    def nullTask1 = Task.Anon { nullString }
    def nullTask2 = Task.Anon { nullTask1() }

    def nullTarget1 = Task { nullString }
    def nullTarget2 = Task { nullTarget1() }
    def nullTarget3 = Task { nullTask1() }
    def nullTarget4 = Task { nullTask2() }

    def nullCommand1() = Task.Command { nullString }
    def nullCommand2() = Task.Command { nullTarget1() }
    def nullCommand3() = Task.Command { nullTask1() }
    def nullCommand4() = Task.Command { nullTask2() }

    override lazy val millDiscover: Discover = Discover[this.type]
  }

  object duplicates extends TestBaseModule {
    object wrapper extends Module {
      object test1 extends Module {
        def test1 = Task {}
      }

      object test2 extends TaskModule {
        override def defaultCommandName() = "test2"
        def test2() = Task.Command {}
      }
    }

    object test3 extends Module {
      def test3 = Task {}
    }

    object test4 extends TaskModule {
      override def defaultCommandName() = "test4"

      def test4() = Task.Command {}
    }
    override lazy val millDiscover: Discover = Discover[this.type]
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
  }

  object nestedTaskCrosses extends TestBaseModule {
    // this is somehow necessary to let Discover see our inner (default) commands
    // I expected, that the identical inherited `millDiscover` is enough, but it isn't
    override lazy val millDiscover: Discover = Discover[this.type]
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

  object StackableOverrides extends TestBaseModule {
    trait X extends Module {
      def f = Task { 1 }
    }
    trait A extends X {
      override def f = Task { super.f() + 2 }
    }

    trait B extends X {
      override def f = Task { super.f() + 3 }
    }
    object m extends A with B {}
  }

  object StackableOverrides2 extends TestBaseModule {
    object A extends Module {
      trait X extends Module {
        def f = Task { 1 }
      }
    }
    object B extends Module {
      trait X extends A.X {
        override def f = Task { super.f() + 2 }
      }
    }

    object m extends B.X {
      override def f = Task { super.f() + 3 }
    }
  }

  object StackableOverrides3 extends TestBaseModule {
    object A extends Module {
      trait X extends Module {
        def f = Task { 1 }
      }
    }
    trait X extends A.X {
      override def f = Task { super.f() + 2 }
    }

    object m extends X {
      override def f = Task { super.f() + 3 }
    }
  }

  object PrivateTasksInMixedTraits extends TestBaseModule {
    trait M1 extends Module {
      private def foo = Task { "foo-m1" }
      def bar = Task { foo() }
    }
    trait M2 extends Module {
      private def foo = Task { "foo-m2" }
      def baz = Task { foo() }
    }
    object mod extends M1 with M2
  }

  object TypedModules extends TestBaseModule {
    trait TypeA extends Module {
      def foo = Task { "foo" }
    }
    trait TypeB extends Module {
      def bar = Task { "bar" }
    }
    trait TypeC extends Module {
      def baz = Task { "baz" }
    }
    trait TypeAB extends TypeA with TypeB

    object typeA extends TypeA
    object typeB extends TypeB
    object typeC extends TypeC {
      object typeA extends TypeA
    }
    object typeAB extends TypeAB
  }

  object TypedCrossModules extends TestBaseModule {
    trait TypeA extends Cross.Module[String] {
      def foo = Task { crossValue }
    }

    trait TypeB extends Module {
      def bar = Task { "bar" }
    }

    trait TypeAB extends TypeA with TypeB

    object typeA extends Cross[TypeA]("a", "b")
    object typeAB extends Cross[TypeAB]("a", "b")

    object inner extends Module {
      object typeA extends Cross[TypeA]("a", "b")
      object typeAB extends Cross[TypeAB]("a", "b")
    }

    trait NestedAB extends TypeAB {
      object typeAB extends Cross[TypeAB]("a", "b")
    }
    object nestedAB extends Cross[NestedAB]("a", "b")
  }

  object TypedInnerModules extends TestBaseModule {
    trait TypeA extends Module {
      def foo = Task { "foo" }
    }
    object typeA extends TypeA
    object typeB extends Module {
      def foo = Task { "foo" }
    }
    object inner extends Module {
      trait TypeA extends Module {
        def foo = Task { "foo" }
      }
      object typeA extends TypeA
    }
  }

  object AbstractModule extends TestBaseModule {
    trait Abstract extends Module {
      lazy val tests: Tests = new Tests {}
      trait Tests extends Module {}
    }

    object concrete extends Abstract {
      override lazy val tests: ConcreteTests = new ConcreteTests {}
      trait ConcreteTests extends Tests {
        object inner extends Module {
          def foo = Task { "foo" }
          object innerer extends Module {
            def bar = Task { "bar" }
          }
        }
      }
    }
  }

}
