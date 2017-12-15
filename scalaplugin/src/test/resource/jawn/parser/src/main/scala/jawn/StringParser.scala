package jawn

/**
 * Basic in-memory string parsing.
 *
 * This is probably the simplest Parser implementation, since there is
 * no UTF-8 decoding, and the data is already fully available.
 *
 * This parser is limited to the maximum string size (~2G). Obviously
 * for large JSON documents it's better to avoid using this parser and
 * go straight from disk, to avoid having to load the whole thing into
 * memory at once. So this limit will probably not be a problem in
 * practice.
 */
private[jawn] final class StringParser[J](s: String) extends SyncParser[J] with CharBasedParser[J] {
  var line = 0
  final def column(i: Int) = i
  final def newline(i: Int) { line += 1 }
  final def reset(i: Int): Int = i
  final def checkpoint(state: Int, i: Int, stack: List[FContext[J]]): Unit = ()
  final def at(i: Int): Char = s.charAt(i)
  final def at(i: Int, j: Int): CharSequence = s.substring(i, j)
  final def atEof(i: Int) = i == s.length
  final def close() = ()
}
