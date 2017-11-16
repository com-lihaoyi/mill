package mill.define


case class Cross[+T](items: T*){
  def flatMap[V](f: T => Cross[V]): Cross[(T, V)] = {
    val flattened = for{
      i <- items
      k <- f(i).items
    } yield (i, k)
    Cross(flattened:_*)
  }
  def map[V](f: T => V): Cross[(T, V)] = {
    Cross(items.map(i => i -> f(i)):_*)
  }
  def withFilter(f: T => Boolean) = {
    Cross(items.filter(f):_*)
  }
}
