package mill.define

import mill.util.TestGraphs
import utest._
import ammonite.ops.pwd
object BasePathTests extends TestSuite{
  val testGraphs = new TestGraphs
  val tests = Tests{
    'singleton - {
      assert(
        testGraphs.singleton.millModuleBasePath ==
        BasePath(pwd / "mill.util.TestGraphs#singleton" / "singleton")
      )
    }
    'separateGroups - {
      assert(
        TestGraphs.separateGroups.millModuleBasePath ==
        BasePath(pwd / "mill.util.TestGraphs.separateGroups" / "separateGroups")
      )
    }
    'TraitWithModuleObject - {
      assert(
        TestGraphs.TraitWithModuleObject.TraitModule.millModuleBasePath ==
        BasePath(pwd / "mill.util.TestGraphs.TraitWithModuleObject" / "TraitWithModuleObject" / "TraitModule")
      )
    }
    'nestedModuleNested - {
      assert(
        TestGraphs.nestedModule.nested.millModuleBasePath ==
        BasePath(pwd / "mill.util.TestGraphs.nestedModule" / "nestedModule" / "nested")
      )
    }
    'nestedModuleInstance - {
      assert(
        TestGraphs.nestedModule.classInstance.millModuleBasePath ==
        BasePath(pwd / "mill.util.TestGraphs.nestedModule" / "nestedModule" / "classInstance")
      )
    }

  }
}

