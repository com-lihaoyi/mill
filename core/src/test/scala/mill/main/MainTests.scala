package mill.main

import mill.Module
import mill.define.Task
import mill.discover.Discovered
import mill.util.TestUtil.test
import utest._

object MainTests extends TestSuite{
  def check[T: Discovered](obj: T,
                           selectorString: String,
                           expected: Either[String, Task[_]]) = {
    val resolved = for{
      args <- mill.Main.parseArgs(selectorString)
      task <- mill.Main.resolve(args, implicitly[Discovered[T]].mirror, obj, Nil, Nil, Nil)
    } yield task
    assert(resolved == expected)
  }
  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    'single - {
      'pos - check(singleton, "single", Right(singleton.single))
      'neg1 - check(singleton, "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(singleton, "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(singleton, "", Left("Selector cannot be empty"))
    }
    'nested - {
      class CanNest extends Module{
        val single = test()
      }
      object outer {
        val single = test()
        object nested extends Module{
          val single = test()
        }
        val classInstance = new CanNest

      }
      'pos1 - check(outer, "single", Right(outer.single))
      'pos2 - check(outer, "nested.single", Right(outer.nested.single))
      'pos3 - check(outer, "classInstance.single", Right(outer.classInstance.single))
      'neg1 - check(outer, "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(outer, "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(outer, "nested.doesntExist", Left("Cannot resolve task nested.doesntExist"))
      'neg4 - check(outer, "classInstance.doesntExist", Left("Cannot resolve task classInstance.doesntExist"))
    }
  }
}
