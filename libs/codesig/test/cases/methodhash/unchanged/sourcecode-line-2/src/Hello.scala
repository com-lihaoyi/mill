package hello

/**
 * Very verbose Scaladoc comment on top of class
 */
object Hello {

  /**
   * less verbose Scaladoc comment for single method
   */
  def main: Int = used

  def used(using line: sourcecode.Line) = 2

  def unused = 1
}
