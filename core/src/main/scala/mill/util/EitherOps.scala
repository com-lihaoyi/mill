package mill.util

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.language.higherKinds

object EitherOps {

  // implementation similar to scala.concurrent.Future#sequence
  def sequence[A, B, M[X] <: TraversableOnce[X]](in: M[Either[A, B]])(
      implicit cbf: CanBuildFrom[M[Either[A, B]], B, M[B]]): Either[A, M[B]] = {
    in.foldLeft[Either[A, mutable.Builder[B, M[B]]]](Right(cbf(in))) {
        case (acc, el) =>
          for (a <- acc; e <- el) yield a += e
      }
      .map(_.result())
  }
}
