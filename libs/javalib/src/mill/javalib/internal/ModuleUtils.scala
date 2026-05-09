package mill.javalib.internal

import mill.api.daemon.internal.internal

import scala.collection.mutable

@internal
object ModuleUtils {

  /**
   * Find all dependencies.
   * The result contains `start` and all its transitive dependencies provided by `deps`,
   * but does not contain duplicates.
   * If it detects a cycle, it throws an exception with a meaningful message containing the cycle trace.
   * @param name The name is used in the exception message only
   * @param start the start element
   * @param deps A function provided the direct dependencies
   * @throws mill.api.MillException if there were cycles in the dependencies
   */
  // FIXME: Remove or consolidate with copy in JvmWorkerImpl
  def recursive[T](name: String, start: T, deps: T => Seq[T]): Seq[T] = {

    val result = mutable.ArrayBuffer[T]()

    /**
     * Prevents visiting the same node twice when it is reachable via
     * multiple paths (deduplication).
     */
    val fullySeen = mutable.Set[T]()

    def dfs(node: T, path: List[T], pathSet: Set[T]): Unit = {
      deps(node).foreach { child =>
        if (pathSet.contains(child)) {
          // cycle!
          val segment = path.takeWhile(_ != child)
          val rendered = (child :: (child :: segment).reverse).mkString(" -> ")
          val msg = s"${name}: cycle detected: ${rendered}"
          println(msg)
          throw new mill.api.MillException(msg)
        }
        if (!fullySeen.contains(child)) {
          fullySeen += child
          result += child
          dfs(child, child :: path, pathSet + child)
        }
      }
    }

    dfs(start, List(start), Set(start))
    result.toSeq.reverse
  }
}
