package mill.util
import TestUtil.test
import mainargs.arg
import mill.testkit.TestBaseModule
import mill.define.{Command, Cross, Discover, DynamicModule, ModuleRef, TaskModule}
import mill.{Module, T}

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
    def up = T { test() }
    def left = T { test(up) }
    def right = T { test(up) }
    def down = T { test(left, right) }
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
    val task1 = T.task { 1 }
    def left = T { task1() }
    val change = test()
    val task2 = T.task { change() }
    def right = T { task1() + task2() + left() + 1 }

  }

  object moduleInitError extends TestBaseModule {
    def rootTarget = T { println("Running rootTarget"); "rootTarget Result" }
    def rootCommand(@arg(positional = true) s: String) =
      T.command { println(s"Running rootCommand $s") }

    object foo extends Module {
      def fooTarget = T { println(s"Running fooTarget"); 123 }
      def fooCommand(@arg(positional = true) s: String) =
        T.command { println(s"Running fooCommand $s") }
      throw new Exception("Foo Boom")
    }

    object bar extends Module {
      def barTarget = T { println(s"Running barTarget"); "barTarget Result" }
      def barCommand(@arg(positional = true) s: String) =
        T.command { println(s"Running barCommand $s") }

      object qux extends Module {
        def quxTarget = T { println(s"Running quxTarget"); "quxTarget Result" }
        def quxCommand(@arg(positional = true) s: String) =
          T.command { println(s"Running quxCommand $s") }
        throw new Exception("Qux Boom")
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object moduleDependencyInitError extends TestBaseModule {

    object foo extends Module {
      def fooTarget = T { println(s"Running fooTarget"); 123 }
      def fooCommand(@arg(positional = true) s: String) =
        T.command { println(s"Running fooCommand $s") }
      throw new Exception("Foo Boom")
    }

    object bar extends Module {
      def barTarget = T {
        println(s"Running barTarget")
        s"${foo.fooTarget()} barTarget Result"
      }
      def barCommand(@arg(positional = true) s: String) = T.command {
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
      def foo = T { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }
  object crossModulePartialInitError extends TestBaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, 4)
    trait MyCross extends Cross.Module[Int] {
      if (crossValue > 2) throw new Exception(s"MyCross Boom $crossValue")
      def foo = T { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }
  object crossModuleSelfInitError extends TestBaseModule {
    object myCross extends Cross[MyCross](1, 2, 3, throw new Exception(s"MyCross Boom"))
    trait MyCross extends Cross.Module[Int] {
      def foo = T { crossValue }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object crossModuleParentInitError extends TestBaseModule {
    object parent extends Module {
      throw new Exception(s"Parent Boom")
      object myCross extends Cross[MyCross](1, 2, 3, 4)
      trait MyCross extends Cross.Module[Int] {
        def foo = T { crossValue }
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object overrideModule extends TestBaseModule {
    trait Base extends Module {
      lazy val inner: BaseInnerModule = new BaseInnerModule {}
      lazy val ignored: ModuleRef[BaseInnerModule] = ModuleRef(new BaseInnerModule {})
      trait BaseInnerModule extends mill.define.Module {
        def baseTarget = T { 1 }
      }
    }
    object sub extends Base {
      override lazy val inner: SubInnerModule = new SubInnerModule {}
      override lazy val ignored: ModuleRef[SubInnerModule] = ModuleRef(new SubInnerModule {})
      trait SubInnerModule extends BaseInnerModule {
        def subTarget = T { 2 }
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object dynamicModule extends TestBaseModule {
    object normal extends DynamicModule {
      object inner extends Module {
        def target = T { 1 }
      }
    }
    object niled extends DynamicModule {
      override def millModuleDirectChildren: Seq[Module] = Nil
      object inner extends Module {
        def target = T { 1 }
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
    val task = T.task { 1 }
    def left = T { task() }
    def right = T { task() + left() + 1 }
  }

  //      _ left
  //     /
  // task -------- right
  object multiTerminalGroup extends TestBaseModule {
    val task = T.task { 1 }
    def left = T { task() }
    def right = T { task() }
  }

  //       _ left _____________
  //      /        \           \
  // task1 -------- right ----- task2
  object multiTerminalBoundary extends TestBaseModule {
    val task1 = T.task { 1 }
    def left = T { task1() }
    def right = T { task1() + left() + 1 }
    val task2 = T.task { left() + right() }
  }

  trait CanNest extends Module {
    def single = T { 1 }
    def invisible: Any = T { 2 }
    def invisible2: mill.define.Task[Int] = T { 3 }
    def invisible3: mill.define.Task[_] = T { 4 }
  }
  object nestedModule extends TestBaseModule {
    def single = T { 5 }
    def invisible: Any = T { 6 }
    object nested extends Module {
      def single = T { 7 }
      def invisible: Any = T { 8 }

    }
    object classInstance extends CanNest

  }
  object doubleNestedModule extends TestBaseModule {
    def single = T { 5 }
    object nested extends Module {
      def single = T { 7 }

      object inner extends Module {
        def single = T { 9 }
      }
    }
  }

  trait BaseModule extends Module {
    def foo = T { Seq("base") }
    def cmd(i: Int) = T.command { Seq("base" + i) }
  }

  object canOverrideSuper extends TestBaseModule with BaseModule {
    override def foo = T { super.foo() ++ Seq("object") }
    override def cmd(i: Int) = T.command { super.cmd(i)() ++ Seq("object" + i) }
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  trait TraitWithModule extends Module { outer =>
    object TraitModule extends Module {
      def testFrameworks = T { Seq("mill.UTestFramework") }
      def test() = T.command { () /*donothing*/ }
    }
  }

  // Make sure nested objects inherited from traits work
  object TraitWithModuleObject extends TestBaseModule with TraitWithModule {
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object nullTasks extends TestBaseModule {
    val nullString: String = null
    def nullTask1 = T.task { nullString }
    def nullTask2 = T.task { nullTask1() }

    def nullTarget1 = T { nullString }
    def nullTarget2 = T { nullTarget1() }
    def nullTarget3 = T { nullTask1() }
    def nullTarget4 = T { nullTask2() }

    def nullCommand1() = T.command { nullString }
    def nullCommand2() = T.command { nullTarget1() }
    def nullCommand3() = T.command { nullTask1() }
    def nullCommand4() = T.command { nullTask2() }

    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object duplicates extends TestBaseModule {
    object wrapper extends Module {
      object test1 extends Module {
        def test1 = T {}
      }

      object test2 extends TaskModule {
        override def defaultCommandName() = "test2"
        def test2() = T.command {}
      }
    }

    object test3 extends Module {
      def test3 = T {}
    }

    object test4 extends TaskModule {
      override def defaultCommandName() = "test4"

      def test4() = T.command {}
    }
    override lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object singleCross extends TestBaseModule {
    object cross extends mill.Cross[Cross]("210", "211", "212")
    trait Cross extends Cross.Module[String] {
      def suffix = T { crossValue }
    }

    object cross2 extends mill.Cross[Cross2]("210", "211", "212")
    trait Cross2 extends Cross.Module[String] {
      override def millSourcePath = super.millSourcePath / crossValue
      def suffix = T { crossValue }
    }
  }

  object nonStringCross extends TestBaseModule {
    object cross extends mill.Cross[Cross](210, 211, 212)
    trait Cross extends Cross.Module[Int] {
      def suffix = T { crossValue }
    }

    object cross2 extends mill.Cross[Cross2](210L, 211L, 212L)
    trait Cross2 extends Cross.Module[Long] {
      override def millSourcePath = super.millSourcePath / crossValue.toString
      def suffix = T { crossValue }
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
      def suffix = T { crossValue }
    }

    object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
    trait BarModule extends MyModule {
      def longSuffix = T { "_" + foo().suffix() }
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
      def suffix = T { scalaVersion + "_" + platform }
    }
  }

  object crossExtension extends TestBaseModule {
    object myCross extends Cross[MyCrossModule]("a", "b")
    trait MyCrossModule extends Cross.Module[String] {
      def param1 = T { "Param Value: " + crossValue }
    }

    object myCrossExtended extends Cross[MyCrossModuleExtended](("a", 1), ("b", 2))
    trait MyCrossModuleExtended extends MyCrossModule with Cross.Module2[String, Int] {
      def param2 = T { "Param Value: " + crossValue2 }
    }

    object myCrossExtendedAgain
        extends Cross[MyCrossModuleExtendedAgain](("a", 1, true), ("b", 2, false))
    trait MyCrossModuleExtendedAgain extends MyCrossModuleExtended
        with Cross.Module3[String, Int, Boolean] {
      def param3 = T { "Param Value: " + crossValue3 }
    }
  }

  object innerCrossModule extends TestBaseModule {
    object myCross extends Cross[MyCrossModule]("a", "b")
    trait MyCrossModule extends Cross.Module[String] {
      object foo extends CrossValue {
        def bar = T { "foo " + crossValue }
      }

      object baz extends CrossValue {
        def bar = T { "baz " + crossValue }
      }
    }

    object myCross2 extends Cross[MyCrossModule2](("a", 1), ("b", 2))
    trait MyCrossModule2 extends Cross.Module2[String, Int] {
      object foo extends InnerCrossModule2 {
        def bar = T { "foo " + crossValue }
        def qux = T { "foo " + crossValue2 }
      }
      object baz extends InnerCrossModule2 {
        def bar = T { "baz " + crossValue }
        def qux = T { "baz " + crossValue2 }
      }
    }

    object myCross3 extends Cross[MyCrossModule3](("a", 1, true), ("b", 2, false))
    trait MyCrossModule3 extends Cross.Module3[String, Int, Boolean] {
      object foo extends InnerCrossModule3 {
        def bar = T { "foo " + crossValue }
        def qux = T { "foo " + crossValue2 }
        def lol = T { "foo " + crossValue3 }
      }
      object baz extends InnerCrossModule3 {
        def bar = T { "baz " + crossValue }
        def qux = T { "baz " + crossValue2 }
        def lol = T { "baz " + crossValue3 }
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
        def suffix = T { scalaVersion + "_" + platform }
      }
    }
  }

  object nestedTaskCrosses extends TestBaseModule {
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
        def suffixCmd(@arg(positional = true) suffix: String = "default"): Command[String] =
          T.command {
            scalaVersion + "_" + platform + "_" + suffix
          }
      }

    }
  }

  object StackableOverrides extends TestBaseModule {
    trait X extends Module {
      def f = T { 1 }
    }
    trait A extends X {
      override def f = T { super.f() + 2 }
    }

    trait B extends X {
      override def f = T { super.f() + 3 }
    }
    object m extends A with B {}
  }

  object StackableOverrides2 extends TestBaseModule {
    object A extends Module {
      trait X extends Module {
        def f = T { 1 }
      }
    }
    object B extends Module {
      trait X extends A.X {
        override def f = T { super.f() + 2 }
      }
    }

    object m extends B.X {
      override def f = T { super.f() + 3 }
    }
  }

  object StackableOverrides3 extends TestBaseModule {
    object A extends Module {
      trait X extends Module {
        def f = T { 1 }
      }
    }
    trait X extends A.X {
      override def f = T { super.f() + 2 }
    }

    object m extends X {
      override def f = T { super.f() + 3 }
    }
  }

  object PrivateTasksInMixedTraits extends TestBaseModule {
    trait M1 extends Module {
      private def foo = T { "foo-m1" }
      def bar = T { foo() }
    }
    trait M2 extends Module {
      private def foo = T { "foo-m2" }
      def baz = T { foo() }
    }
    object mod extends M1 with M2
  }

  object TypedModules extends TestBaseModule {
    trait TypeA extends Module {
      def foo = T { "foo" }
    }
    trait TypeB extends Module {
      def bar = T { "bar" }
    }
    trait TypeC extends Module {
      def baz = T { "baz" }
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
      def foo = T { crossValue }
    }

    trait TypeB extends Module {
      def bar = T { "bar" }
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
      def foo = T { "foo" }
    }
    object typeA extends TypeA
    object typeB extends Module {
      def foo = T { "foo" }
    }
    object inner extends Module {
      trait TypeA extends Module {
        def foo = T { "foo" }
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
          def foo = T { "foo" }
          object innerer extends Module {
            def bar = T { "bar" }
          }
        }
      }
    }
  }

}
