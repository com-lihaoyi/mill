package mill.main

/**
  * Class that wraps each Mill build file.
  */
trait MainWrapper[T]{
  val discovered: mill.discover.Discovered[T]
  // Stub to make sure Ammonite has something to call after it evaluates a script,
  // even if it does nothing...
  def $main() = Iterator[String]()
  lazy val mapping = discovered.mapping(this.asInstanceOf[T])
}
