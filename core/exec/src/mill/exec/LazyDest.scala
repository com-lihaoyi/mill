package mill.exec

import mill.api.ExecutionPaths

private[exec] final class LazyDest(dest0: () => os.Path) {
  private var initializedDest = Option.empty[os.Path]

  def get(): os.Path = synchronized {
    initializedDest match {
      case Some(dest) => dest
      case None =>
        val dest = dest0()
        os.makeDir.all(dest)
        initializedDest = Some(dest)
        dest
    }
  }
}

private[exec] object LazyDest {
  def fromPaths(paths: Option[ExecutionPaths]): LazyDest = new LazyDest(() =>
    paths match {
      case Some(paths) => paths.dest
      case None => throw Exception("No `dest` folder available here")
    }
  )
}
