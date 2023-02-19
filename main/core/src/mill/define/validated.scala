package mill.define

import scala.annotation.Annotation

/**
 * Annotate Mill targets which return a [[mill.api.PathRef]] or a [[Seq]] or [[mill.api.Agg]] of it.
 * It results in additional checks for already cached results to make sure they exists and are up to date.
 */
class validated extends Annotation
