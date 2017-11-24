package mill.define

case class Cross[+T](items: List[(List[Any], T)]){
  def flatMap[V](f: T => Cross[V]): Cross[V] = new Cross(
    items.flatMap{
      case (l, v) => f(v).items.map{case (l2, v2) => (l2 ::: l, v2)}
    }
  )
  def map[V](f: T => V): Cross[V] = new Cross(items.map{case (l, v) => (l, f(v))})
  def withFilter(f: T => Boolean): Cross[T] = new Cross(items.filter(t => f(t._2)))

  def apply(input: List[Any]): T =
    items.find(_._1 == input).map(_._2)
      .getOrElse(null.asInstanceOf[T])
}
object Cross{
  def apply[T](t: T*) = new Cross(t.map(i => List(i) -> i).toList)
}
