package jawn

import scala.annotation.{switch, tailrec}

/**
 * Trait used when the data to be parsed is in UTF-16.
 *
 * This parser provides parseString(). Like ByteBasedParser it has
 * fast/slow paths for string parsing depending on whether any escapes
 * are present.
 *
 * It is simpler than ByteBasedParser.
 */
trait CharBasedParser[J] extends Parser[J] {

  private[this] final val charBuilder = new CharBuilder()

  /**
   * See if the string has any escape sequences. If not, return the
   * end of the string. If so, bail out and return -1.
   *
   * This method expects the data to be in UTF-16 and accesses it as
   * chars.
   */
  protected[this] final def parseStringSimple(i: Int, ctxt: FContext[J]): Int = {
    var j = i
    var c = at(j)
    while (c != '"') {
      if (c < ' ') return die(j, s"control char (${c.toInt}) in string")
      if (c == '\\') return -1
      j += 1
      c = at(j)
    }
    j + 1
  }

  /**
   * Parse a string that is known to have escape sequences.
   */
  protected[this] final def parseStringComplex(i: Int, ctxt: FContext[J]): Int = {
    var j = i + 1
    val sb = charBuilder.reset()

    var c = at(j)
    while (c != '"') {
      if (c < ' ') {
        die(j, s"control char (${c.toInt}) in string")
      } else if (c == '\\') {
        (at(j + 1): @switch) match {
          case 'b' => { sb.append('\b'); j += 2 }
          case 'f' => { sb.append('\f'); j += 2 }
          case 'n' => { sb.append('\n'); j += 2 }
          case 'r' => { sb.append('\r'); j += 2 }
          case 't' => { sb.append('\t'); j += 2 }

          case '"' => { sb.append('"'); j += 2 }
          case '/' => { sb.append('/'); j += 2 }
          case '\\' => { sb.append('\\'); j += 2 }

          // if there's a problem then descape will explode
          case 'u' => { sb.append(descape(at(j + 2, j + 6))); j += 6 }

          case c => die(j, s"illegal escape sequence (\\$c)")
        }
      } else {
        // this case is for "normal" code points that are just one Char.
        //
        // we don't have to worry about surrogate pairs, since those
        // will all be in the ranges D800–DBFF (high surrogates) or
        // DC00–DFFF (low surrogates).
        sb.append(c)
        j += 1
      }
      j = reset(j)
      c = at(j)
    }
    ctxt.add(sb.makeString)
    j + 1
  }

  /**
   * Parse the string according to JSON rules, and add to the given
   * context.
   *
   * This method expects the data to be in UTF-16, and access it as
   * Char. It performs the correct checks to make sure that we don't
   * interpret a multi-char code point incorrectly.
   */
  protected[this] final def parseString(i: Int, ctxt: FContext[J]): Int = {
    val k = parseStringSimple(i + 1, ctxt)
    if (k != -1) {
      ctxt.add(at(i + 1, k - 1))
      k
    } else {
      parseStringComplex(i, ctxt)
    }
  }
}
