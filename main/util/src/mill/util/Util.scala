package mill.util


object Util{

  def isInteractive() = System.console() != null

  val newLine = System.lineSeparator()

  val java9OrAbove = !System.getProperty("java.specification.version").startsWith("1.")
}
