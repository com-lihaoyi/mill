package mill.runner.worker.api

trait Snip {
  def text: String | Null
  def start: Int
  def end: Int

  final def applyTo(s: String, replacement: String): String =
    s.patch(start, replacement.padTo(end - start, ' '), end - start)
}
