package jawn

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.charset.Charset
import scala.annotation.{switch, tailrec}
import scala.util.Try

case class ParseException(msg: String, index: Int, line: Int, col: Int) extends Exception(msg)

case class IncompleteParseException(msg: String) extends Exception(msg)

/**
 * Parser implements a state machine for correctly parsing JSON data.
 *
 * The trait relies on a small number of methods which are left
 * abstract, and which generalize parsing based on whether the input
 * is in Bytes or Chars, coming from Strings, files, or other input.
 * All methods provided here are protected, so different parsers can
 * choose which functionality to expose.
 *
 * Parser is parameterized on J, which is the type of the JSON AST it
 * will return. Jawn can produce any AST for which a Facade[J] is
 * available.
 *
 * The parser trait does not hold any state itself, but particular
 * implementations will usually hold state. Parser instances should
 * not be reused between parsing runs.
 *
 * For now the parser requires input to be in UTF-8. This requirement
 * may eventually be relaxed.
 */
abstract class Parser[J] {

  protected[this] final val utf8 = Charset.forName("UTF-8")

  /**
   * Read the byte/char at 'i' as a Char.
   *
   * Note that this should not be used on potential multi-byte
   * sequences.
   */
  protected[this] def at(i: Int): Char

  /**
   * Read the bytes/chars from 'i' until 'j' as a String.
   */
  protected[this] def at(i: Int, j: Int): CharSequence

  /**
   * Return true iff 'i' is at or beyond the end of the input (EOF).
   */
  protected[this] def atEof(i: Int): Boolean

  /**
   * The reset() method is used to signal that we're working from the
   * given position, and any previous data can be released. Some
   * parsers (e.g.  StringParser) will ignore release, while others
   * (e.g. PathParser) will need to use this information to release
   * and allocate different areas.
   */
  protected[this] def reset(i: Int): Int

  /**
   * The checkpoint() method is used to allow some parsers to store
   * their progress.
   */
  protected[this] def checkpoint(state: Int, i: Int, stack: List[FContext[J]]): Unit

  /**
   * Should be called when parsing is finished.
   */
  protected[this] def close(): Unit

  /**
   * Valid parser states.
   */
  @inline protected[this] final val ARRBEG = 6
  @inline protected[this] final val OBJBEG = 7
  @inline protected[this] final val DATA = 1
  @inline protected[this] final val KEY = 2
  @inline protected[this] final val SEP = 3
  @inline protected[this] final val ARREND = 4
  @inline protected[this] final val OBJEND = 5

  protected[this] def newline(i: Int): Unit
  protected[this] def line(): Int
  protected[this] def column(i: Int): Int

  protected[this] final val HexChars: Array[Int] = {
    val arr = new Array[Int](128)
    var i = 0
    while (i < 10) { arr(i + '0') = i; i += 1 }
    i = 0
    while (i < 16) { arr(i + 'a') = 10 + i; arr(i + 'A') = 10 + i; i += 1 }
    arr
  }

  /**
   * Used to generate error messages with character info and offsets.
   */
  protected[this] def die(i: Int, msg: String): Nothing = {
    val y = line() + 1
    val x = column(i) + 1
    val s = "%s got %s (line %d, column %d)" format (msg, at(i), y, x)
    throw ParseException(s, i, y, x)
  }

  /**
   * Used to generate messages for internal errors.
   *
   * This should only be used in situations where a possible bug in
   * the parser was detected. For errors in user-provided JSON, use
   * die().
   */
  protected[this] def error(msg: String) =
    sys.error(msg)

  /**
   * Parse the given number, and add it to the given context.
   *
   * We don't actually instantiate a number here, but rather pass the
   * string of for future use. Facades can choose to be lazy and just
   * store the string. This ends up being way faster and has the nice
   * side-effect that we know exactly how the user represented the
   * number.
   */
  protected[this] final def parseNum(i: Int, ctxt: FContext[J])(implicit facade: Facade[J]): Int = {
    var j = i
    var c = at(j)
    var decIndex = -1
    var expIndex = -1

    if (c == '-') {
      j += 1
      c = at(j)
    }
    if (c == '0') {
      j += 1
      c = at(j)
    } else if ('1' <= c && c <= '9') {
      while ('0' <= c && c <= '9') { j += 1; c = at(j) }
    } else {
      die(i, "expected digit")
    }

    if (c == '.') {
      decIndex = j - i
      j += 1
      c = at(j)
      if ('0' <= c && c <= '9') {
        while ('0' <= c && c <= '9') { j += 1; c = at(j) }
      } else {
        die(i, "expected digit")
      }
    }

    if (c == 'e' || c == 'E') {
      expIndex = j - i
      j += 1
      c = at(j)
      if (c == '+' || c == '-') {
        j += 1
        c = at(j)
      }
      if ('0' <= c && c <= '9') {
        while ('0' <= c && c <= '9') { j += 1; c = at(j) }
      } else {
        die(i, "expected digit")
      }
    }

    ctxt.add(facade.jnum(at(i, j), decIndex, expIndex))
    j
  }

  /**
   * Parse the given number, and add it to the given context.
   *
   * This method is a bit slower than parseNum() because it has to be
   * sure it doesn't run off the end of the input.
   *
   * Normally (when operating in rparse in the context of an outer
   * array or object) we don't need to worry about this and can just
   * grab characters, because if we run out of characters that would
   * indicate bad input. This is for cases where the number could
   * possibly be followed by a valid EOF.
   *
   * This method has all the same caveats as the previous method.
   */
  protected[this] final def parseNumSlow(i: Int, ctxt: FContext[J])(implicit facade: Facade[J]): Int = {
    var j = i
    var c = at(j)
    var decIndex = -1
    var expIndex = -1

    if (c == '-') {
      // any valid input will require at least one digit after -
      j += 1
      c = at(j)
    }
    if (c == '0') {
      j += 1
      if (atEof(j)) {
        ctxt.add(facade.jnum(at(i, j), decIndex, expIndex))
        return j
      }
      c = at(j)
    } else if ('1' <= c && c <= '9') {
      while ('0' <= c && c <= '9') {
        j += 1
        if (atEof(j)) {
          ctxt.add(facade.jnum(at(i, j), decIndex, expIndex))
          return j
        }
        c = at(j)
      }
    } else {
      die(i, "expected digit")
    }

    if (c == '.') {
      // any valid input will require at least one digit after .
      decIndex = j - i
      j += 1
      c = at(j)
      if ('0' <= c && c <= '9') {
        while ('0' <= c && c <= '9') {
          j += 1
          if (atEof(j)) {
            ctxt.add(facade.jnum(at(i, j), decIndex, expIndex))
            return j
          }
          c = at(j)
        }
      } else {
        die(i, "expected digit")
      }
    }

    if (c == 'e' || c == 'E') {
      // any valid input will require at least one digit after e, e+, etc
      expIndex = j - i
      j += 1
      c = at(j)
      if (c == '+' || c == '-') {
        j += 1
        c = at(j)
      }
      if ('0' <= c && c <= '9') {
        while ('0' <= c && c <= '9') {
          j += 1
          if (atEof(j)) {
            ctxt.add(facade.jnum(at(i, j), decIndex, expIndex))
            return j
          }
          c = at(j)
        }
      } else {
        die(i, "expected digit")
      }
    }

    ctxt.add(facade.jnum(at(i, j), decIndex, expIndex))
    j
  }

  /**
   * Generate a Char from the hex digits of "\u1234" (i.e. "1234").
   *
   * NOTE: This is only capable of generating characters from the basic plane.
   * This is why it can only return Char instead of Int.
   */
  protected[this] final def descape(s: CharSequence): Char = {
    val hc = HexChars
    var i = 0
    var x = 0
    while (i < 4) {
      x = (x << 4) | hc(s.charAt(i).toInt)
      i += 1
    }
    x.toChar
  }

  /**
   * Parse the JSON string starting at 'i' and save it into 'ctxt'.
   */
  protected[this] def parseString(i: Int, ctxt: FContext[J]): Int

  /**
   * Parse the JSON constant "true".
   *
   * Note that this method assumes that the first character has already been checked.
   */
  protected[this] final def parseTrue(i: Int)(implicit facade: Facade[J]): J =
    if (at(i + 1) == 'r' && at(i + 2) == 'u' && at(i + 3) == 'e') {
      facade.jtrue
    } else {
      die(i, "expected true")
    }

  /**
   * Parse the JSON constant "false".
   *
   * Note that this method assumes that the first character has already been checked.
   */
  protected[this] final def parseFalse(i: Int)(implicit facade: Facade[J]): J =
    if (at(i + 1) == 'a' && at(i + 2) == 'l' && at(i + 3) == 's' && at(i + 4) == 'e') {
      facade.jfalse
    } else {
      die(i, "expected false")
    }

  /**
   * Parse the JSON constant "null".
   *
   * Note that this method assumes that the first character has already been checked.
   */
  protected[this] final def parseNull(i: Int)(implicit facade: Facade[J]): J =
    if (at(i + 1) == 'u' && at(i + 2) == 'l' && at(i + 3) == 'l') {
      facade.jnull
    } else {
      die(i, "expected null")
    }

  /**
   * Parse and return the next JSON value and the position beyond it.
   */
  protected[this] final def parse(i: Int)(implicit facade: Facade[J]): (J, Int) = try {
    (at(i): @switch) match {
      // ignore whitespace
      case ' ' => parse(i + 1)
      case '\t' => parse(i + 1)
      case '\r' => parse(i + 1)
      case '\n' => newline(i); parse(i + 1)

      // if we have a recursive top-level structure, we'll delegate the parsing
      // duties to our good friend rparse().
      case '[' => rparse(ARRBEG, i + 1, facade.arrayContext() :: Nil)
      case '{' => rparse(OBJBEG, i + 1, facade.objectContext() :: Nil)

      // we have a single top-level number
      case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
        val ctxt = facade.singleContext()
        val j = parseNumSlow(i, ctxt)
        (ctxt.finish, j)

      // we have a single top-level string
      case '"' =>
        val ctxt = facade.singleContext()
        val j = parseString(i, ctxt)
        (ctxt.finish, j)

      // we have a single top-level constant
      case 't' => (parseTrue(i), i + 4)
      case 'f' => (parseFalse(i), i + 5)
      case 'n' => (parseNull(i), i + 4)

      // invalid
      case _ => die(i, "expected json value")
    }
  } catch {
    case _: IndexOutOfBoundsException =>
      throw IncompleteParseException("exhausted input")
  }

  /**
   * Tail-recursive parsing method to do the bulk of JSON parsing.
   *
   * This single method manages parser states, data, etc. Except for
   * parsing non-recursive values (like strings, numbers, and
   * constants) all important work happens in this loop (or in methods
   * it calls, like reset()).
   *
   * Currently the code is optimized to make use of switch
   * statements. Future work should consider whether this is better or
   * worse than manually constructed if/else statements or something
   * else. Also, it may be possible to reorder some cases for speed
   * improvements.
   */
  @tailrec
  protected[this] final def rparse(state: Int, j: Int, stack: List[FContext[J]])(implicit facade: Facade[J]): (J, Int) = {
    val i = reset(j)
    checkpoint(state, i, stack)

    val c = at(i)

    if (c == '\n') {
      newline(i)
      rparse(state, i + 1, stack)
    } else if (c == ' ' || c == '\t' || c == '\r') {
      rparse(state, i + 1, stack)
    } else if (state == DATA) {
      // we are inside an object or array expecting to see data
      if (c == '[') {
        rparse(ARRBEG, i + 1, facade.arrayContext() :: stack)
      } else if (c == '{') {
        rparse(OBJBEG, i + 1, facade.objectContext() :: stack)
      } else {
        val ctxt = stack.head

        if ((c >= '0' && c <= '9') || c == '-') {
          val j = parseNum(i, ctxt)
          rparse(if (ctxt.isObj) OBJEND else ARREND, j, stack)
        } else if (c == '"') {
          val j = parseString(i, ctxt)
          rparse(if (ctxt.isObj) OBJEND else ARREND, j, stack)
        } else if (c == 't') {
          ctxt.add(parseTrue(i))
          rparse(if (ctxt.isObj) OBJEND else ARREND, i + 4, stack)
        } else if (c == 'f') {
          ctxt.add(parseFalse(i))
          rparse(if (ctxt.isObj) OBJEND else ARREND, i + 5, stack)
        } else if (c == 'n') {
          ctxt.add(parseNull(i))
          rparse(if (ctxt.isObj) OBJEND else ARREND, i + 4, stack)
        } else {
          die(i, "expected json value")
        }
      }
    } else if (
      (c == ']' && (state == ARREND || state == ARRBEG)) ||
      (c == '}' && (state == OBJEND || state == OBJBEG))
    ) {
      // we are inside an array or object and have seen a key or a closing
      // brace, respectively.
      if (stack.isEmpty) {
        error("invalid stack")
      } else {
        val ctxt1 = stack.head
        val tail = stack.tail

        if (tail.isEmpty) {
          (ctxt1.finish, i + 1)
        } else {
          val ctxt2 = tail.head
          ctxt2.add(ctxt1.finish)
          rparse(if (ctxt2.isObj) OBJEND else ARREND, i + 1, tail)
        }
      }
    } else if (state == KEY) {
      // we are in an object expecting to see a key.
      if (c == '"') {
        val j = parseString(i, stack.head)
        rparse(SEP, j, stack)
      } else {
        die(i, "expected \"")
      }
    } else if (state == SEP) {
      // we are in an object just after a key, expecting to see a colon.
      if (c == ':') {
        rparse(DATA, i + 1, stack)
      } else {
        die(i, "expected :")
      }
    } else if (state == ARREND) {
      // we are in an array, expecting to see a comma (before more data).
      if (c == ',') {
        rparse(DATA, i + 1, stack)
      } else {
        die(i, "expected ] or ,")
      }
    } else if (state == OBJEND) {
      // we are in an object, expecting to see a comma (before more data).
      if (c == ',') {
        rparse(KEY, i + 1, stack)
      } else {
        die(i, "expected } or ,")
      }
    } else if (state == ARRBEG) {
      // we are starting an array, expecting to see data or a closing bracket.
      rparse(DATA, i, stack)
    } else {
      // we are starting an object, expecting to see a key or a closing brace.
      rparse(KEY, i, stack)
    }
  }
}


object Parser {

  def parseUnsafe[J](s: String)(implicit facade: Facade[J]): J =
    new StringParser(s).parse()

  def parseFromString[J](s: String)(implicit facade: Facade[J]): Try[J] =
    Try(new StringParser[J](s).parse)

  def parseFromCharSequence[J](cs: CharSequence)(implicit facade: Facade[J]): Try[J] =
    Try(new CharSequenceParser[J](cs).parse)

  def parseFromPath[J](path: String)(implicit facade: Facade[J]): Try[J] =
    Try(ChannelParser.fromFile[J](new File(path)).parse)

  def parseFromFile[J](file: File)(implicit facade: Facade[J]): Try[J] =
    Try(ChannelParser.fromFile[J](file).parse)

  def parseFromChannel[J](ch: ReadableByteChannel)(implicit facade: Facade[J]): Try[J] =
    Try(ChannelParser.fromChannel[J](ch).parse)

  def parseFromByteBuffer[J](buf: ByteBuffer)(implicit facade: Facade[J]): Try[J] =
    Try(new ByteBufferParser[J](buf).parse)

  def async[J](mode: AsyncParser.Mode)(implicit facade: Facade[J]): AsyncParser[J] =
    AsyncParser[J](mode)
}
