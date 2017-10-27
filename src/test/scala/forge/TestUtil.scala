package forge

import forge.Target.test
import utest.assert

import scala.collection.mutable

object TestUtil {

  def checkTopological(targets: OSet[Target[_]]) = {
    val seen = mutable.Set.empty[Target[_]]
    for(t <- targets.items.reverseIterator){
      seen.add(t)
      for(upstream <- t.inputs){
        assert(!seen(upstream))
      }
    }
  }

  def makeGraphs() = {
    object singleton {
      val single = T{ test() }
    }
    object pair {
      val up = T{ test() }
      val down = T{ test(up) }
    }

    object anonTriple{
      val up = T{ test() }
      val down = T{ test(test(up)) }
    }
    object diamond{
      val up = T{ test() }
      val left = T{ test(up) }
      val right = T{ test(up) }
      val down = T{ test(left, right) }
    }
    object anonDiamond{
      val up = T{ test() }
      val down = T{ test(test(up), test(up)) }
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
    object bigSingleTerminal{
      val a = T{ test(test(), test()) }
      val b = T{ test(test()) }
      val e = T{
        val c = test(a)
        val d = test(a)
        test(test(test(), test(c)), test(test(c, test(d, b))))
      }
      val f = T{
        test(test(test(), test(e)))
      }
      val i = T{
        val g = test()
        val h = test(g, e)
        test(test(g), test(test(h)))
      }
      val j = T{
        test(test(i), test(i, f), test(f))
      }
    }

    (singleton, pair, anonTriple, diamond, anonDiamond, bigSingleTerminal)
  }
}
