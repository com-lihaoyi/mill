package mill.contrib.bloop

import io.circe.{Decoder, Encoder, Json}
import upickle.core.Visitor
import upickle.default

trait CirceCompat {

  // Converts from a Circe encoder to a uPickle one
  implicit def circeWriter[T: Encoder]: default.Writer[T] =
    new default.Writer[T] {
      override def write0[V](out: Visitor[_, V], v: T) =
        ujson.circe.CirceJson.transform(Encoder[T].apply(v), out)
    }

  // Converts from a Circe decoder to a uPickle one
  implicit def circeReader[T: Decoder]: default.Reader[T] =
    new default.Reader.Delegate[Json, T](
      ujson.circe.CirceJson.map(Decoder[T].decodeJson).map(_.right.get))

}

object CirceCompat extends CirceCompat
