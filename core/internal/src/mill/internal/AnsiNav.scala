package mill.internal

import java.io.Writer

// Reference https://gist.github.com/fnky/458719343aabd01cfb17a3a4f7296797
class AnsiNav(output: Writer) {
  def control(n: Int, c: Char): Unit = output.write(AnsiNav.control(n, c))

  /**
   * Move up `n` squares
   */
  def up(n: Int): Any = if (n != 0) output.write(AnsiNav.up(n))

  /**
   * Move down `n` squares
   */
  def down(n: Int): Any = if (n != 0) output.write(AnsiNav.down(n))

  /**
   * Move right `n` squares
   */
  def right(n: Int): Any = if (n != 0) output.write(AnsiNav.right(n))

  /**
   * Move left `n` squares
   */
  def left(n: Int): Any = if (n != 0) output.write(AnsiNav.left(n))

  /**
   * Clear the screen
   *
   * n=0: clear from cursor to end of screen
   * n=1: clear from cursor to start of screen
   * n=2: clear entire screen
   */
  def clearScreen(n: Int): Unit = output.write(AnsiNav.clearScreen(n))

  /**
   * Clear the current line
   *
   * n=0: clear from cursor to end of line
   * n=1: clear from cursor to start of line
   * n=2: clear entire line
   */
  def clearLine(n: Int): Unit = output.write(AnsiNav.clearLine(n))
}

object AnsiNav {
  def control(n: Int, c: Char): String = "\u001b[" + n + c
  def up(n: Int): String = if (n != 0) control(n, 'A') else ""
  def down(n: Int): String = if (n != 0) control(n, 'B') else ""
  def right(n: Int): String = if (n != 0) control(n, 'C') else ""
  def left(n: Int): String = if (n != 0) control(n, 'D') else ""
  def clearScreen(n: Int): String = control(n, 'J')
  def clearLine(n: Int): String = control(n, 'K')
}
