package mill.internal

import scala.collection.mutable

// Adapted from
// https://github.com/indy256/codelibrary/blob/c52247216258e84aac442a23273b7d8306ef757b/java/src/SCCTarjan.java
// Converted to iterative to avoid StackOverflowError on large graphs
object Tarjans {
  def apply(graph: IndexedSeq[Array[Int]]): Array[Array[Int]] = {
    val n = graph.length
    val visited = new Array[Boolean](n)
    val sccStack = mutable.ArrayBuffer.empty[Int]
    var time = 0
    val lowlink = new Array[Int](n)
    val components = Array.newBuilder[Array[Int]]

    // Iterative DFS using explicit call stack
    // Each frame stores: (node, childIndex, isComponentRoot)
    val callStack = mutable.ArrayDeque.empty[(Int, Int, Boolean)]

    for (u <- 0 until n) {
      if (!visited(u)) {
        lowlink(u) = time
        time += 1
        visited(u) = true
        sccStack.append(u)
        callStack.append((u, 0, true))

        while (callStack.nonEmpty) {
          val (node, childIdx, isRoot) = callStack.last
          val children = graph(node)

          if (childIdx < children.length) {
            val v = children(childIdx)
            callStack(callStack.length - 1) = (node, childIdx + 1, isRoot)
            if (!visited(v)) {
              lowlink(v) = time
              time += 1
              visited(v) = true
              sccStack.append(v)
              callStack.append((v, 0, true))
            } else {
              if (lowlink(node) > lowlink(v)) {
                lowlink(node) = lowlink(v)
                callStack(callStack.length - 1) = (node, childIdx + 1, false)
              }
            }
          } else {
            // Done with all children of this node
            if (isRoot) {
              val component = Array.newBuilder[Int]
              var done = false
              while (!done) {
                val x = sccStack.last
                sccStack.remove(sccStack.length - 1)
                component.addOne(x)
                lowlink(x) = Integer.MAX_VALUE
                if (x == node) done = true
              }
              components.addOne(component.result())
            }
            callStack.removeLast()
            // Propagate lowlink to parent
            if (callStack.nonEmpty) {
              val (parentNode, parentIdx, parentIsRoot) = callStack.last
              if (lowlink(parentNode) > lowlink(node)) {
                lowlink(parentNode) = lowlink(node)
                callStack(callStack.length - 1) = (parentNode, parentIdx, false)
              }
            }
          }
        }
      }
    }
    components.result()
  }
}
