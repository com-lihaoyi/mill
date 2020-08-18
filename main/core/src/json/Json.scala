package mill
package json

trait JsonReader[A] {
  def read(s: String): A
}
object JsonReader {
  def apply[A](implicit ev: JsonReader[A]): JsonReader[A] = ev

  implicit def fromUpickleReader[A: upickle.default.Reader]: JsonReader[A] = new JsonReader[A] {
    override def read(s: String): A = upickle.default.read(s)
  }
}


trait JsonWriter[A] {
  def write(a: A): String
}
object JsonWriter {
  def apply[A](implicit ev: JsonWriter[A]): JsonWriter[A] = ev

  implicit def fromUpickleWriter[A: upickle.default.Writer]: JsonWriter[A] = new JsonWriter[A] {
    override def write(a: A): String = upickle.default.write(a)
  }
}

trait JsonRW[A] extends JsonReader[A] with JsonWriter[A]
object JsonRW extends JsonRWlowPriorityImplicits {
  def apply[A](implicit ev: JsonRW[A]): JsonRW[A] = ev

  implicit def fromUpickleRW[A: upickle.default.ReadWriter]: JsonRW[A] = {
    join(JsonReader.fromUpickleReader, JsonWriter.fromUpickleWriter)
  }
}

sealed trait JsonRWlowPriorityImplicits {
  implicit def join[A](implicit r: JsonReader[A], w: JsonWriter[A]): JsonRW[A] = new JsonRW[A] {
    override def write(a: A): String = w.write(a)
    override def read(s: String): A = r.read(s)
  }
}
