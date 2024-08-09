package mill.util
import TestUtil.test
import mill.define.{Command, Cross, Discover, DynamicModule, ModuleRef, TaskModule}
import mill.{Module, T, Task}
import mill.moduledefs.NullaryMethod
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
  object singleton extends TestUtil.BaseModule {
    @NullaryMethod val single = test()
  }

  object bactickIdentifiers extends TestUtil.BaseModule {
    @NullaryMethod val `up-target` = test()
    @NullaryMethod val `a-down-target` = test(`up-target`)
    @NullaryMethod val `invisible&` = test()
    object `nested-module` extends Module {
      @NullaryMethod val `nested-target` = test()
    }
  }

  // up---down
  object pair extends TestUtil.BaseModule {
    @NullaryMethod val up = test()
    @NullaryMethod val down = test(up)
  }

  // up---o---down
  object anonTriple extends TestUtil.BaseModule {
    val up = test()
    val down = test(test.anon(up))
  }

  //   left
  //   /   \
  // up    down
  //   \   /
  //   right
  object diamond extends TestUtil.BaseModule {
    @NullaryMethod val up = test()
    @NullaryMethod val left = test(up)
    @NullaryMethod val right = test(up)
    @NullaryMethod val down = test(left, right)
  }

  //    o
  //   / \
  // up   down
  //   \ /
  //    o
  object anonDiamond extends TestUtil.BaseModule {
    @NullaryMethod val up = test()
    @NullaryMethod val down = test(test.anon(up), test.anon(up))
  }

  object defCachedDiamond extends TestUtil.BaseModule {
    def up = Task { test() }
    def left = Task { test(up) }
    def right = Task { test(up) }
    def down = Task { test(left, right) }
  }

  object borkedCachedDiamond2 extends TestUtil.BaseModule {
    def up = test()
    def left = test(up)
    def right = test(up)
    def down = test(left, right)
  }

  object borkedCachedDiamond3 extends TestUtil.BaseModule {
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
  object bigSingleTerminal extends TestUtil.BaseModule {
    @NullaryMethod val a = test(test.anon(), test.anon())
    @NullaryMethod val b = test(test.anon())
    @NullaryMethod val e = {
      val c = test.anon(a)
      val d = test.anon(a)
      test(
        test.anon(test.anon(), test.anon(c)),
        test.anon(test.anon(c, test.anon(d, b)))
      )
    }
    val f = test(test.anon(test.anon(), test.anon(e)))

    @NullaryMethod val i = {
      val g = test.anon()
      val h = test.anon(g, e)
      test(test.anon(g), test.anon(test.anon(h)))
    }
    @NullaryMethod val j = test(test.anon(i), test.anon(i, f), test.anon(f))
  }
  //        _ left _
  //       /        \
  //  task1 -------- right
  //               _/
  // change - task2
  object separateGroups extends TestUtil.BaseModule {
    @NullaryMethod val task1 = Task.anon { 1 }
    def left = Task { task1() }
    @NullaryMethod val change = test()
    @NullaryMethod val task2 = Task.anon { change() }
    def right = Task { task1() + task2() + left() + 1 }

  }

  object moduleInitError extends TestUtil.BaseModule {
    def rootTarget = Task { println("Running rootTarget"); "rootTarget Result" }
    def rootCommand(s: String) = Task.command { println(s"Running rootCommand $s") }

    object foo extends Module {
      def fooTarget = Task { println(s"Running fooTarget"); 123 }
      def fooCommand(s: String) = Task.command { println(s"Running fooCommand $s") }
      throw new Exception("Foo Boom")
    }

    object bar extends Module {
      def barTarget = Task { println(s"Running barTarget"); "barTarget Result" }
      def barCommand(s: String) = Task.command { println(s"Running barCommand $s") }

      object qux extends Module {
        def quxTarget = Task { println(s"Running quxTarget"); "quxTarget Result" }
        def quxCommand(s: String) = Task.command { println(s"Running quxCommand $s") }
        throw new Exception("Qux Boom")
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object moduleDependencyInitError extends TestUtil.BaseModule {

    object foo extends Module {
      def fooTarget = Task { println(s"Running fooTarget"); 123 }
      def fooCommand(s: String) = Task.command { println(s"Running fooCommand $s") }
      throw new Exception("Foo Boom")
    }

    object bar extends Module {
      def barTarget = Task {
        println(s"Running barTarget")
        s"${foo.fooTarget()} barTarget Result"
      }
      def barCommand(s: String) = Task.command {
        foo.fooCommand(s)()
        println(s"Running barCommand $s")
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object crossModuleSimpleInitError extends TestUtil.BaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, 4) {
      throw new Exception(s"MyCross Boom")
    }
    trait MyCross extends Cross.Module[Int] {
      def foo = Task { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }
  object crossModulePartialInitError extends TestUtil.BaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, 4)
    trait MyCross extends Cross.Module[Int] {
      if (crossValue > 2) throw new Exception(s"MyCross Boom $crossValue")
      def foo = Task { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }
  object crossModuleSelfInitError extends TestUtil.BaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, throw new Exception(s"MyCross Boom"))
    trait MyCross extends Cross.Module[Int] {
      def foo = Task { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object crossModuleParentInitError extends TestUtil.BaseModule {
    object parent extends Module {
      throw new Exception(s"Parent Boom")
      object myCross extends Cross[MyCross](1, 2, 3, 4)
      trait MyCross extends Cross.Module[Int] {
        def foo = Task { crossValue }
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object overrideModule extends TestUtil.BaseModule {
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

  object dynamicModule extends TestUtil.BaseModule {
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
  object triangleTask extends TestUtil.BaseModule {
    val task = Task.anon { 1 }
    def left = Task { task() }
    def right = Task { task() + left() + 1 }
  }

  //      _ left
  //     /
  // task -------- right
  object multiTerminalGroup extends TestUtil.BaseModule {
    val task = Task.anon { 1 }
    def left = Task { task() }
    def right = Task { task() }
  }

  //       _ left _____________
  //      /        \           \
  // task1 -------- right ----- task2
  object multiTerminalBoundary extends TestUtil.BaseModule {
    val task1 = Task.anon { 1 }
    def left = Task { task1() }
    def right = Task { task1() + left() + 1 }
    val task2 = Task.anon { left() + right() }
  }

  trait CanNest extends Module {
    def single = Task { 1 }
    def invisible: Any = Task { 2 }
    def visible2: mill.define.Task[Int] = Task { 3 }
    def visible3: mill.define.Task[_] = Task { 4 }
  }
  object nestedModule extends TestUtil.BaseModule {
    def single = Task { 5 }
    def invisible: Any = Task { 6 }
    object nested extends Module {
      def single = Task { 7 }
      def invisible: Any = Task { 8 }

    }
    object classInstance extends CanNest

  }
  object doubleNestedModule extends TestUtil.BaseModule {
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
    def cmd(i: Int) = Task.command { Seq("base" + i) }
  }

  object canOverrideSuper extends TestUtil.BaseModule with BaseModule {
    override def foo = Task { super.foo() ++ Seq("object") }
    override def cmd(i: Int) = Task.command { super.cmd(i)() ++ Seq("object" + i) }
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  trait TraitWithModule extends Module { outer =>
    object TraitModule extends Module {
      def testFrameworks = Task { Seq("mill.UTestFramework") }
      def test() = Task.command { () /*donothing*/ }
    }
  }

  // Make sure nested objects inherited from traits work
  object TraitWithModuleObject extends TestUtil.BaseModule with TraitWithModule {
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object nullTasks extends TestUtil.BaseModule {
    val nullString: String = null
    def nullTask1 = Task.anon { nullString }
    def nullTask2 = Task.anon { nullTask1() }

    def nullTarget1 = Task { nullString }
    def nullTarget2 = Task { nullTarget1() }
    def nullTarget3 = Task { nullTask1() }
    def nullTarget4 = Task { nullTask2() }

    def nullCommand1() = Task.command { nullString }
    def nullCommand2() = Task.command { nullTarget1() }
    def nullCommand3() = Task.command { nullTask1() }
    def nullCommand4() = Task.command { nullTask2() }

    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object duplicates extends TestUtil.BaseModule {
    object wrapper extends Module {
      object test1 extends Module {
        def test1 = Task {}
      }

      object test2 extends TaskModule {
        override def defaultCommandName() = "test2"
        def test2() = Task.command {}
      }
    }

    object test3 extends Module {
      def test3 = Task {}
    }

    object test4 extends TaskModule {
      override def defaultCommandName() = "test4"

      def test4() = Task.command {}
    }
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object singleCross extends TestUtil.BaseModule {
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

  object nonStringCross extends TestUtil.BaseModule {
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

  object crossResolved extends TestUtil.BaseModule {
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
  object doubleCross extends TestUtil.BaseModule {
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

  object crossExtension extends TestUtil.BaseModule {
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

  object innerCrossModule extends TestUtil.BaseModule {
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

  object nestedCrosses extends TestUtil.BaseModule {
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

  object nestedTaskCrosses extends TestUtil.BaseModule {
    // this is somehow necessary to let Discover see our inner (default) commands
    // I expected, that the identical inherited `millDiscover` is enough, but it isn't
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
    object cross1 extends mill.Cross[Cross1]("210", "211", "212")
    trait Cross1 extends mill.Cross.Module[String] {
      def scalaVersion = crossValue

      object cross2 extends mill.Cross[Cross2]("jvm", "js", "native")
      trait Cross2 extends mill.Cross.Module[String] with TaskModule {
        def platform = crossValue
        override def defaultCommandName(): String = "suffixCmd"
        def suffixCmd(suffix: String = "default"): Command[String] = Task.command {
          scalaVersion + "_" + platform + "_" + suffix
        }
      }

    }
  }

  object StackableOverrides extends TestUtil.BaseModule {
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

  object PrivateTasksInMixedTraits extends TestUtil.BaseModule {
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

  object TypedModules extends TestUtil.BaseModule {
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

  object TypedCrossModules extends TestUtil.BaseModule {
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

  object TypedInnerModules extends TestUtil.BaseModule {
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

  object AbstractModule extends TestUtil.BaseModule {
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
