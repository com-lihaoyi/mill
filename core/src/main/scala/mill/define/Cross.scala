package mill.define

sealed trait Cross[T]{
  def flatMap[V](f: T => Cross[V]): Cross[V] = new Cross.FlatMapping(this, f)
  def map[V](f: T => V): Cross[V] = new Cross.Mapping(this, f)
  def withFilter(f: T => Boolean): Cross[T] = new Cross.Filtering(this, f)

}
object Cross{
  class Listing[T](val items: Seq[T]) extends Cross[T]
  class Mapping[T, V](val parent: Cross[T], val f: T => V) extends Cross[V]
  class FlatMapping[T, V](val parent: Cross[T], val f: T => Cross[V]) extends Cross[V]
  class Filtering[T](val parent: Cross[T], val f: T => Boolean) extends Cross[T]

  def apply[T](t: T*) = new Cross.Listing(t)

  def evaluate[T](c: Cross[T]): List[(List[Any], T)] = c match{
    case c: Listing[T] =>  c.items.map(i => List(i) -> i).toList
    case c: Mapping[_, T] => evaluate(c.parent).map{case (l, v) => (l, c.f(v))}
    case c: FlatMapping[_, T] =>
      evaluate(c.parent).flatMap{
        case (l, v) => evaluate(c.f(v)).map{case (l2, v2) => (l2 ::: l, v2)}
      }
    case c: Filtering[T] => evaluate(c.parent).filter(t => c.f(t._2))
  }
  def test() = {
    val example1 = evaluate(
      for(a <- Cross(1, 2, 3))
      yield a.toString
    )
    pprint.log(example1)

    val example2 = evaluate(
      for{
        a <- Cross(1, 2, 3)
        b <- Cross("A", "B", "C")
      } yield b * a
    )
    pprint.pprintln(example2)

    val example3 = evaluate(
      for{
        a <- Cross(1, 2, 3)
        b <- Cross("A", "B", "C")
        c <- Cross(true, false)
      } yield b * a + c
    )
    pprint.log(example3)

    val example4 = evaluate(
      for{
        a <- Cross(1, 2, 3)
        b <- Cross("A", "B", "C")
        if !(a == 2 && b == "B")
      } yield b * a
    )
    pprint.log(example4)

    val example5 = evaluate(
      for{
        (a, b) <- for(a <- Cross(1, 2, 3); b <- Cross("A", "B", "C")) yield (a, b)
        c <- Cross(true, false)
      } yield b * a + c
    )
    pprint.log(example5)
  }
}