package mill.internal

// Adapted from
// https://github.com/indy256/codelibrary/blob/c52247216258e84aac442a23273b7d8306ef757b/java/src/SCCTarjan.java
// Converted to iterative to avoid StackOverflowError on large graphs
// Optimized to use flat arrays instead of tuples to avoid boxing/allocation
object Tarjans {
  def apply(graph: IndexedSeq[Array[Int]]): Array[Array[Int]] = {
    val n = graph.length
    val visited = new Array[Boolean](n)
    val lowlink = new Array[Int](n)
    val components = Array.newBuilder[Array[Int]]
    var time = 0

    // SCC stack using flat array + size counter
    val sccStack = new Array[Int](n)
    var sccSize = 0

    // DFS call stack using 3 parallel flat arrays instead of tuples.
    // Max depth is n (each node pushed at most once).
    val stackNode = new Array[Int](n)
    val stackChildIdx = new Array[Int](n)
    val stackIsRoot = new Array[Boolean](n)
    var stackSize = 0

    for (u <- 0 until n) {
      if (!visited(u)) {
        lowlink(u) = time
        time += 1
        visited(u) = true
        sccStack(sccSize) = u
        sccSize += 1
        stackNode(0) = u
        stackChildIdx(0) = 0
        stackIsRoot(0) = true
        stackSize = 1

        while (stackSize > 0) {
          val top = stackSize - 1
          val node = stackNode(top)
          val childIdx = stackChildIdx(top)
          val children = graph(node)

          if (childIdx < children.length) {
            val v = children(childIdx)
            stackChildIdx(top) = childIdx + 1
            if (!visited(v)) {
              lowlink(v) = time
              time += 1
              visited(v) = true
              sccStack(sccSize) = v
              sccSize += 1
              stackNode(stackSize) = v
              stackChildIdx(stackSize) = 0
              stackIsRoot(stackSize) = true
              stackSize += 1
            } else {
              if (lowlink(node) > lowlink(v)) {
                lowlink(node) = lowlink(v)
                stackIsRoot(top) = false
              }
            }
          } else {
            // Done with all children of this node
            if (stackIsRoot(top)) {
              val component = Array.newBuilder[Int]
              var done = false
              while (!done) {
                sccSize -= 1
                val x = sccStack(sccSize)
                component.addOne(x)
                lowlink(x) = Integer.MAX_VALUE
                if (x == node) done = true
              }
              components.addOne(component.result())
            }
            stackSize -= 1
            // Propagate lowlink to parent
            if (stackSize > 0) {
              val parentTop = stackSize - 1
              if (lowlink(stackNode(parentTop)) > lowlink(node)) {
                lowlink(stackNode(parentTop)) = lowlink(node)
                stackIsRoot(parentTop) = false
              }
            }
          }
        }
      }
    }
    components.result()
  }
}
