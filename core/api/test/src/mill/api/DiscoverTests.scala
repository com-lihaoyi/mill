package mill.api

import mill.api.TestGraphs
import Task.Simple
import mill.testkit.TestRootModule
import utest.*

trait DiscoverVisibilityModule extends Module {
  def publicTask = Task { 1 }
  protected def protectedTask = Task { 2 }
  private[mill] def packageTask = Task { 3 }
  private def privateTask = Task { 4 }
}

object DiscoverVisibilityRoot extends TestRootModule with DiscoverVisibilityModule {
  lazy val millDiscover = Discover[this.type]
}

trait DiscoverReferencedModule extends Module {
  def referencedPublicTask = Task { 1 }
  private[mill] def referencedPackageTask = Task { 2 }
}

object DiscoverModuleRefRoot extends TestRootModule {
  def referenced: ModuleRef[DiscoverReferencedModule] =
    sys.error("discovery must not evaluate ModuleRef")
  lazy val millDiscover = Discover[this.type]
}

trait DiscoverAbstractModule extends Module {
  def concreteTask = Task { 1 }
  protected def abstractTask: Task.Simple[Int]
}

object DiscoverAbstractRoot {
  lazy val millDiscover = Discover[DiscoverAbstractModule]
}

object DiscoverTests extends TestSuite {
  val tests = Tests {
    def check[T <: Module](m: T)(tasks: (T => Simple[?])*) = {
      val discovered = m.moduleInternal.simpleTasks
      val expected = tasks.map(_(m)).toSet
      assert(discovered == expected)
    }
    test("singleton") {
      check(TestGraphs.singleton)(_.single)
    }
    test("backtickIdentifiers") {
      check(TestGraphs.bactickIdentifiers)(
        _.`up-task`,
        _.`a-down-task`,
        _.`nested-module`.`nested-task`
      )
    }
    test("separateGroups") {
      check(TestGraphs.triangleTask)(_.left, _.right)
    }
    test("TraitWithModuleObject") {
      check(TestGraphs.TraitWithModuleObject)(_.TraitModule.testFrameworks)
    }
    test("nestedModule") {
      check(TestGraphs.nestedModule)(_.single, _.nested.single, _.classInstance.single)
    }
    test("singleCross") {
      check(TestGraphs.singleCross)(
        _.cross("210").suffix,
        _.cross("211").suffix,
        _.cross("212").suffix,
        _.cross2("210").suffix,
        _.cross2("211").suffix,
        _.cross2("212").suffix
      )
    }
    test("doubleCross") {
      check(TestGraphs.doubleCross)(
        _.cross("210", "jvm").suffix,
        _.cross("210", "js").suffix,
        _.cross("211", "jvm").suffix,
        _.cross("211", "js").suffix,
        _.cross("212", "jvm").suffix,
        _.cross("212", "js").suffix,
        _.cross("212", "native").suffix
      )
    }
    test("nestedCrosses") {
      check(TestGraphs.nestedCrosses)(
        _.cross("210").cross2("jvm").suffix,
        _.cross("210").cross2("js").suffix,
        _.cross("210").cross2("native").suffix,
        _.cross("211").cross2("jvm").suffix,
        _.cross("211").cross2("js").suffix,
        _.cross("211").cross2("native").suffix,
        _.cross("212").cross2("jvm").suffix,
        _.cross("212").cross2("js").suffix,
        _.cross("212").cross2("native").suffix
      )
    }
    test("path task names retain non-public tasks") {
      val classInfo =
        DiscoverVisibilityRoot.millDiscover.classInfo(classOf[DiscoverVisibilityModule])

      assert(
        classInfo.declaredTaskNameSet == Set("publicTask"),
        classInfo.pathTaskNameSet == Set(
          "publicTask",
          "protectedTask",
          "packageTask"
        ),
        DiscoverVisibilityRoot.millDiscover.allTaskNames == Set("publicTask")
      )
    }
    test("module refs contribute path task metadata") {
      val classInfo =
        DiscoverModuleRefRoot.millDiscover.classInfo(classOf[DiscoverReferencedModule])

      assert(
        classInfo.declaredTaskNameSet == Set("referencedPublicTask"),
        classInfo.pathTaskNameSet == Set("referencedPublicTask", "referencedPackageTask"),
        DiscoverModuleRefRoot.millDiscover.allTaskNames == Set("referencedPublicTask")
      )
    }
    test("path task names exclude abstract declarations") {
      val classInfo =
        DiscoverAbstractRoot.millDiscover.classInfo(classOf[DiscoverAbstractModule])

      assert(
        classInfo.declaredTaskNameSet == Set("concreteTask"),
        classInfo.pathTaskNameSet == Set("concreteTask")
      )
    }
    test("legacy class info falls back to CLI-visible tasks") {
      val classInfo = Discover.ClassInfo(Nil, Seq(Discover.TaskInfo("publicTask")))
      assert(classInfo.pathTaskNameSet == Set("publicTask"))
    }

  }
}
