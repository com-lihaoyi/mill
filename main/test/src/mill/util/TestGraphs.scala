package mill.util
import TestUtil.test
import mill.define.{Cross, Discover}
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
class TestGraphs(){
  // single
  object singleton extends TestUtil.BaseModule {
    val single = test()
  }

  // up---down
  object pair extends TestUtil.BaseModule{
    val up = test()
    val down = test(up)
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
  object anonDiamond extends TestUtil.BaseModule {
    val up = test()
    val down = test(test.anon(up), test.anon(up))
  }

  object defCachedDiamond extends TestUtil.BaseModule {
    def up = T{ test() }
    def left = T{ test(up) }
    def right = T{ test(up) }
    def down = T{ test(left, right) }
  }


  object borkedCachedDiamond2 extends TestUtil.BaseModule  {
    def up = test()
    def left = test(up)
    def right = test(up)
    def down = test(left, right)
  }

  object borkedCachedDiamond3 extends TestUtil.BaseModule  {
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
  object separateGroups extends TestUtil.BaseModule {
    val task1 = T.task{ 1 }
    def left = T{ task1() }
    val change = test()
    val task2 = T.task{ change() }
    def right = T{ task1() + task2() + left() + 1 }

  }
}


object TestGraphs{
  //      _ left _
  //     /        \
  // task -------- right
  object triangleTask extends TestUtil.BaseModule {
    val task = T.task{ 1 }
    def left = T{ task() }
    def right = T{ task() + left() + 1 }
  }


  //      _ left
  //     /
  // task -------- right
  object multiTerminalGroup extends TestUtil.BaseModule {
    val task = T.task{ 1 }
    def left = T{ task() }
    def right = T{ task() }
  }

  //       _ left _____________
  //      /        \           \
  // task1 -------- right ----- task2
  object multiTerminalBoundary extends TestUtil.BaseModule {
    val task1 = T.task{ 1 }
    def left = T{ task1() }
    def right = T{ task1() + left() + 1 }
    val task2 = T.task{ left() + right() }
  }


  trait CanNest extends Module{
    def single = T{ 1 }
    def invisible: Any = T{ 2 }
    def invisible2: mill.define.Task[Int] = T{ 3 }
    def invisible3: mill.define.Task[_] = T{ 4 }
  }
  object nestedModule extends TestUtil.BaseModule {
    def single = T{ 5 }
    def invisible: Any = T{ 6 }
    object nested extends Module{
      def single = T{ 7 }
      def invisible: Any = T{ 8 }

    }
    object classInstance extends CanNest

  }

  trait BaseModule extends Module {
    def foo = T{ Seq("base") }
    def cmd(i: Int) = T.command{ Seq("base" + i) }
  }

  object canOverrideSuper extends TestUtil.BaseModule with BaseModule {
    override def foo = T{ super.foo() ++ Seq("object") }
    override def cmd(i: Int) = T.command{ super.cmd(i)() ++ Seq("object" + i) }
    def millDiscover: Discover[this.type] = Discover[this.type]
  }

  trait TraitWithModule extends Module{ outer =>
    object TraitModule extends Module{
      def testFramework = T{ "mill.UTestFramework" }
      def test() = T.command{ ()/*donothing*/ }
    }
  }


  // Make sure nested objects inherited from traits work
  object TraitWithModuleObject extends TestUtil.BaseModule with TraitWithModule{
    def millDiscover: Discover[this.type] = Discover[this.type]
  }


  object singleCross extends TestUtil.BaseModule {
    object cross extends mill.Cross[Cross]("210", "211", "212")
    class Cross(scalaVersion: String) extends Module{
      def suffix = T{ scalaVersion }
    }
  }
  object crossResolved extends TestUtil.BaseModule {
    trait MyModule extends Module{
      def crossVersion: String
      implicit object resolver extends mill.define.Cross.Resolver[MyModule]{
        def resolve[V <: MyModule](c: Cross[V]): V = c.itemMap(List(crossVersion))
      }
    }

    object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
    class FooModule(val crossVersion: String) extends MyModule{
      def suffix = T{ crossVersion }
    }

    object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
    class BarModule(val crossVersion: String) extends MyModule{
      def longSuffix = T{ "_" + foo().suffix() }
    }
  }
  object doubleCross extends TestUtil.BaseModule {
    val crossMatrix = for{
      scalaVersion <- Seq("210", "211", "212")
      platform <- Seq("jvm", "js", "native")
      if !(platform == "native" && scalaVersion != "212")
    } yield (scalaVersion, platform)
    object cross extends mill.Cross[Cross](crossMatrix:_*)
    class Cross(scalaVersion: String, platform: String) extends Module{
      def suffix = T{ scalaVersion + "_" + platform }
    }
  }

  object nestedCrosses extends TestUtil.BaseModule {
    object cross extends mill.Cross[Cross]("210", "211", "212")
    class Cross(scalaVersion: String) extends mill.Module{
      object cross2 extends mill.Cross[Cross]("jvm", "js", "native")
      class Cross(platform: String) extends mill.Module{
        def suffix = T{ scalaVersion + "_" + platform }
      }
    }
  }
}
