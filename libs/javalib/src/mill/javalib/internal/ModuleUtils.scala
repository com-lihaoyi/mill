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
        seenModules: Vector[T],
        seenSet: Set[T],
        toAnalyze: List[(List[T], Set[T], List[T])]
    ): Vector[T] = {
      toAnalyze match {
        case Nil => seenModules
        case traces :: rest =>
          traces match {
            case (_, _, Nil) => rec(seenModules, seenSet, rest)
            case (trace, traceSet, cand :: remaining) =>
              if (traceSet.contains(cand)) {
                // cycle!
                val rendered =
                  (cand :: (cand :: trace.takeWhile(_ != cand)).reverse).mkString(" -> ")
                val msg = s"${name}: cycle detected: ${rendered}"
                println(msg)
                throw new mill.api.MillException(msg)
              }
              val seen = seenSet.contains(cand)
              rec(
                if (seen) seenModules else seenModules :+ cand,
                if (seen) seenSet else seenSet + cand,
                toAnalyze =
                  ((cand :: trace, traceSet + cand, deps(cand).toList)) ::
                    (trace, traceSet, remaining) ::
                    rest
              )
          }
      }
    }

    rec(
      seenModules = Vector.empty,
      seenSet = Set.empty,
      toAnalyze = List((List(start), Set(start), deps(start).toList))
    ).reverse
  }
}
