package mill.runner.worker.api

// @internal
case class ImportTree(
    prefix: Seq[(String, Int)],
    mappings: Seq[(String, Option[String])],
    start: Int,
    end: Int
)
