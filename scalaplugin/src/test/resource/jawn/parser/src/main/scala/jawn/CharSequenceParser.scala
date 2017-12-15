package jawn

/**
 * Lazy character sequence parsing.
 *
 * This is similar to StringParser, but acts on character sequences.
 */
private[jawn] final class CharSequenceParser[J](cs: CharSequence) extends SyncParser[J] with CharBasedParser[J] {
  var line = 0
  final def column(i: Int) = i
  final def newline(i: Int) { line += 1 }
  final def reset(i: Int): Int = i
  final def checkpoint(state: Int, i: Int, stack: List[FContext[J]]): Unit = ()
  final def at(i: Int): Char = cs.charAt(i)
  final def at(i: Int, j: Int): CharSequence = cs.subSequence(i, j)
  final def atEof(i: Int) = i == cs.length
  final def close() = ()
}
