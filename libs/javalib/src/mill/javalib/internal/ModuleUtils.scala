package mill.javalib.internal

import mill.api.daemon.internal.internal

import scala.annotation.tailrec

@internal
object ModuleUtils {

  /**
   * Find all dependencies.
   * The result contains `start` and all its transitive dependencies provided by `deps`,
   * but does not contain duplicates.
   * If it detects a cycle, it throws an exception with a meaningful message containing the cycle trace.
   * @param name The nane is used in the exception message only
   * @param start the start element
   * @param deps A function provided the direct dependencies
   * @throws mill.api.MillException if there were cycles in the dependencies
   */
  // FIXME: Remove or consolidate with copy in JvmWorkerImpl
  def recursive[T](name: String, start: T, deps: T => Seq[T]): Seq[T] = {

    @tailrec def rec(
        seenModules: Set[T],
        result: List[T],
        toAnalyze: List[(List[T], List[T])]
    ): List[T] = {
      toAnalyze match {
        case Nil => result
        case traces :: rest =>
          traces match {
            case (_, Nil) => rec(seenModules, result, rest)
            case (trace, cand :: remaining) =>
              if (trace.contains(cand)) {
                // cycle!
                val rendered =
                  (cand :: (cand :: trace.takeWhile(_ != cand)).reverse).mkString(" -> ")
                val msg = s"${name}: cycle detected: ${rendered}"
                println(msg)
                throw new mill.api.MillException(msg)
              }
              val alreadySeen = seenModules.contains(cand)
              rec(
                if (alreadySeen) seenModules else seenModules + cand,
                if (alreadySeen) result else cand :: result,
                toAnalyze = ((cand :: trace, deps(cand).toList)) :: (trace, remaining) :: rest
              )
          }
      }
    }

    rec(
      seenModules = Set.empty,
      result = Nil,
      toAnalyze = List((List(start), deps(start).toList))
    )
  }
}
