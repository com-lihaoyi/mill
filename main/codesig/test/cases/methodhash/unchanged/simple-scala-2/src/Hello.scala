package hello

/**
 * Very verbose Scaladoc comment on top of class
 */
object Hello {
  /**
   * less verbose Scaladoc comment for single method
   */
  def main: Int = {
    println(1)
    println(2)
    println(3)
    println(4)
    used
  }

  def used = 2

  def unused = 1
}
