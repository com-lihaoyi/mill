package hello

object Hello {
  def main: Int = used
  def used(using line: sourcecode.Line) = 2
  def unused = 1
}
