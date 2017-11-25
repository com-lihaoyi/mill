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
    val mirror = implicitly[Discovered[T]].mirror
    val resolved = for{
      args <- mill.Main.parseArgs(selectorString)
      crossSelectors = args.collect{case Right(x) => x.toList}
      task <- mill.Main.resolve(args, mirror, obj, Nil, crossSelectors, Nil)
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
    'cross - {
      'single - {
        object outer{
          val cross =
            for(jarLabel <- mill.define.Cross("jarA", "jarB", "jarC"))
            yield new mill.Module{
              val target = test()
            }
        }
        'pos1 - check(outer, "cross[jarA].target", Right(outer.cross(List("jarA")).target))
        'pos2 - check(outer, "cross[jarB].target", Right(outer.cross(List("jarB")).target))
        'neg1 - check(outer, "cross[jarA].doesntExist", Left("Cannot resolve task cross[jarA].doesntExist"))
  //      'neg2 - check(outer, "cross[doesntExist].doesntExist", Left("Cannot resolve cross cross[doesntExist]"))
  //      'neg2 - check(outer, "cross[doesntExist].target", Left("Cannot resolve cross cross[doesntExist]"))
      }
      'double - {
        object outer{
          val cross =
            for{
              jarLabel <- mill.define.Cross("jarA", "jarB", "jarC")
              tag <- mill.define.Cross("jvm", "js", "native")
            } yield new mill.Module{
              val target = test()
            }
        }
        'pos1 - check(
          outer,
          "cross[jvm,jarA].target",
          Right(outer.cross(List("jvm", "jarA")).target)
        )
        'pos2 - check(
          outer,
          "cross[jvm,jarB].target",
          Right(outer.cross(List("jvm", "jarB")).target)
        )
      }
      'nested - {
        object outer{
          val cross =
            for(jarLabel <- mill.define.Cross("jarA", "jarB", "jarC"))
            yield new mill.Module{
              val cross2 =
                for(tag <- mill.define.Cross("jvm", "js", "native"))
                yield new mill.Module{
                  val target = test()
                }

            }
        }
        'pos1 - check(
          outer,
          "cross[jarA].cross2[js].target",
          Right(outer.cross(List("jarA")).cross2(List("js")).target)
        )
        'pos2 - check(
          outer,
          "cross[jarB].cross2[jvm].target",
          Right(outer.cross(List("jarB")).cross2(List("jvm")).target)
        )
      }
    }

  }
}
