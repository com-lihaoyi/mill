package mill.main

/**
  * Class that wraps each Mill build file.
  */
trait MainWrapper[T]{
  val discovered: mill.discover.Discovered[T]
  val interpApi: ammonite.interp.InterpAPI
  val mapping = discovered.mapping(this.asInstanceOf[T])

  implicit val replApplyHandler: mill.main.ReplApplyHandler =
    new mill.main.ReplApplyHandler(
      new mill.eval.Evaluator(
        ammonite.ops.pwd / 'out,
        mapping,
        new mill.util.PrintLogger(true)
      )
    )
}
