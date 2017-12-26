package mill.main

/**
  * Class that wraps each Mill build file.
  */
trait MainWrapper[T]{
  val discovered: mill.discover.Discovered[T]
  lazy val mapping = discovered.mapping(this.asInstanceOf[T])
}
