package jawn

import scala.annotation.{switch, tailrec}
import scala.collection.mutable

/**
 * SyncParser extends Parser to do all parsing synchronously.
 *
 * Most traditional JSON parser are synchronous, and expect to receive
 * all their input before returning. SyncParser[J] still leaves
 * Parser[J]'s methods abstract, but adds a public methods for users
 * to call to actually parse JSON.
 */
abstract class SyncParser[J] extends Parser[J] {

  /**
   * Parse the JSON document into a single JSON value.
   *
   * The parser considers documents like '333', 'true', and '"foo"' to be
   * valid, as well as more traditional documents like [1,2,3,4,5]. However,
   * multiple top-level objects are not allowed.
   */
  final def parse()(implicit facade: Facade[J]): J = {
    val (value, i) = parse(0)
    var j = i
    while (!atEof(j)) {
      (at(j): @switch) match {
        case '\n' => newline(j); j += 1
        case ' ' | '\t' | '\r' => j += 1
        case _ => die(j, "expected whitespace or eof")
      }
    }
    if (!atEof(j)) die(j, "expected eof")
    close()
    value
  }
}
