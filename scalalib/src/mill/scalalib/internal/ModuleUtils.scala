package mill.scalalib.internal

import mill.api.{BuildScriptException, experimental}
import mill.define.{Module, Segments}

import scala.annotation.tailrec

@mill.api.internal
object ModuleUtils {

  /**
   * Computes a display name for a module which is also disambiguates foreign modules.
   */
  def moduleDisplayName(module: Module): String = {
    (module.millModuleShared.value.getOrElse(Segments()) ++ module.millModuleSegments).render
  }

  def recursive[T <: Module](name: String, start: T, deps: T => Seq[T]): Seq[T] = {

    @tailrec def rec(
        seenModules: List[T],
        toAnalyze: List[(List[T], List[T])]
    ): List[T] = {
      toAnalyze match {
        case Nil => seenModules
        case traces :: rest =>
          traces match {
            case (_, Nil) => rec(seenModules, rest)
            case (trace, cand :: remaining) =>
              if (trace.contains(cand)) {
                // cycle!
                val rendered =
                  (cand :: (cand :: trace.takeWhile(_ != cand)).reverse).mkString(" -> ")
                val msg = s"${name}: cycle detected: ${rendered}"
                println(msg)
                throw new BuildScriptException(msg)
              }
              rec(
                seenModules ++ Seq(cand),
                toAnalyze = ((cand :: trace, deps(cand).toList)) :: (trace, remaining) :: rest
              )
          }
      }
    }

    rec(
      seenModules = List(),
      toAnalyze = List((List(start), deps(start).toList))
    ).reverse
  }
}
