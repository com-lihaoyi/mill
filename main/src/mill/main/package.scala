package mill

package object main {
  @deprecated("Renamed to EvaluatorTokenReader.", "Mill 0.11.0-M8")
  type EvaluatorScopts[T] = EvaluatorTokenReader[T]
}
