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

  class TestGraphs(){
    object singleton {
      val single = test()
    }
    object pair {
      val up = test()
      val down = test(up)
    }

    object anonTriple{
      val up = test()
      val down = test(test(up))
    }
    object diamond{
      val up = test()
      val left = test(up)
      val right = test(up)
      val down = test(left, right)
    }
    object anonDiamond{
      val up = test()
      val down = test(test(up), test(up))
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
      val a = test(test(), test())
      val b = test(test())
      val e = {
        val c = test(a)
        val d = test(a)
        test(test(test(), test(c)), test(test(c, test(d, b))))
      }
      val f = test(test(test(), test(e)))

      val i = {
        val g = test()
        val h = test(g, e)
        test(test(g), test(test(h)))
      }
      val j = test(test(i), test(i, f), test(f))
    }

    (singleton, pair, anonTriple, diamond, anonDiamond, bigSingleTerminal)
  }
}
