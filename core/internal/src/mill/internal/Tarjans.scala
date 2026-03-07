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

    // DFS call stack using 3 parallel flat arrays instead of ArrayDeque[(Int, Int, Boolean)]
    var stackCapacity = math.min(n, 1024)
    var stackNode = new Array[Int](stackCapacity)
    var stackChildIdx = new Array[Int](stackCapacity)
    var stackIsRoot = new Array[Boolean](stackCapacity)
    var stackSize = 0

    def pushFrame(node: Int, childIdx: Int, isRoot: Boolean): Unit = {
      if (stackSize == stackCapacity) {
        stackCapacity *= 2
        stackNode = java.util.Arrays.copyOf(stackNode, stackCapacity)
        stackChildIdx = java.util.Arrays.copyOf(stackChildIdx, stackCapacity)
        stackIsRoot = java.util.Arrays.copyOf(stackIsRoot, stackCapacity)
      }
      stackNode(stackSize) = node
      stackChildIdx(stackSize) = childIdx
      stackIsRoot(stackSize) = isRoot
      stackSize += 1
    }

    for (u <- 0 until n) {
      if (!visited(u)) {
        lowlink(u) = time
        time += 1
        visited(u) = true
        sccStack(sccSize) = u
        sccSize += 1
        pushFrame(u, 0, true)

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
              pushFrame(v, 0, true)
            } else {
              if (lowlink(node) > lowlink(v)) {
                lowlink(node) = lowlink(v)
                stackIsRoot(top) = false
              }
            }
          } else {
            // Done with all children of this node
            val isRoot = stackIsRoot(top)
            if (isRoot) {
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
              val parentNode = stackNode(parentTop)
              if (lowlink(parentNode) > lowlink(node)) {
                lowlink(parentNode) = lowlink(node)
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
