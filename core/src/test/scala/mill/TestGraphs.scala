package mill

import mill.TestUtil.test

class TestGraphs(){
  // single
  object singleton {
    val single = test()
  }

  // up---down
  object pair {
    val up = test()
    val down = test(up)
  }

  // up---o---down
  object anonTriple{
    val up = test()
    val down = test(test(up))
  }

  //   left
  //   /   \
  // up    down
  //   \   /
  //   right
  object diamond{
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
  object anonDiamond{
    val up = test()
    val down = test(test(up), test(up))
  }

  object defCachedDiamond extends Module{
    def up = T{ test() }
    def left = T{ test(up) }
    def right = T{ test(up) }
    def down = T{ test(left, right) }
  }


  object borkedCachedDiamond2 extends Module {
    def up = test()
    def left = test(up)
    def right = test(up)
    def down = test(left, right)
  }

  object borkedCachedDiamond3 {
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
}

