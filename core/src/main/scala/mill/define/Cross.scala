package mill.define

case class Cross[+T](items: List[(List[Any], T)]){
  def flatMap[V](f: T => Cross[V]): Cross[V] = new Cross(
    items.flatMap{
      case (l, v) => f(v).items.map{case (l2, v2) => (l2 ::: l, v2)}
    }
  )
  def map[V](f: T => V): Cross[V] = new Cross(items.map{case (l, v) => (l, f(v))})
  def withFilter(f: T => Boolean): Cross[T] = new Cross(items.filter(t => f(t._2)))

  def applyOpt(input: Any*): Option[T] = {
    val inputList = input.toList
    items.find(_._1 == inputList).map(_._2)
  }
  def apply(input: Any*): T = {
    applyOpt(input:_*).getOrElse(
      throw new Exception(
        "Unknown set of cross values: " + input +
        " not in known values\n" + items.map(_._1).mkString("\n")
      )
    )
  }
}
object Cross{
  def apply[T](t: T*) = new Cross(t.map(i => List(i) -> i).toList)
}

class CrossModule[T, V](constructor: T => V, cases: T*)
extends Cross[V](cases.toList.map(x => (List(x), constructor(x))))