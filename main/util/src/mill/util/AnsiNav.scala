package mill.util

import java.io.{PrintStream, OutputStream, OutputStreamWriter, Writer}

// Reference https://gist.github.com/fnky/458719343aabd01cfb17a3a4f7296797
case class AnsiNav(output: PrintStream) {
  def saveCursor() = output.print("\u001b7")
  def restoreCursor() = output.print("\u001b8")
  def control(n: Int, c: Char): Unit = output.print("\u001b[" + n + c)

  /**
   * Move up `n` squares
   */
  def up(n: Int): Any = if (n == 0) "" else control(n, 'A')

  /**
   * Move down `n` squares
   */
  def down(n: Int): Any = if (n == 0) "" else control(n, 'B')

  /**
   * Move right `n` squares
   */
  def right(n: Int): Any = if (n == 0) "" else control(n, 'C')

  /**
   * Move left `n` squares
   */
  def left(n: Int): Any = if (n == 0) "" else control(n, 'D')

  /**
   * Clear the screen
   *
   * n=0: clear from cursor to end of screen
   * n=1: clear from cursor to start of screen
   * n=2: clear entire screen
   */
  def clearScreen(n: Int): Unit = control(n, 'J')

  /**
   * Clear the current line
   *
   * n=0: clear from cursor to end of line
   * n=1: clear from cursor to start of line
   * n=2: clear entire line
   */
  def clearLine(n: Int): Unit = control(n, 'K')
}
