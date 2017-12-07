package better.files

import java.io.{InputStream, LineNumberReader, Reader, StringReader}
import java.nio.charset.Charset
import java.time.format.DateTimeFormatter
import java.util.StringTokenizer

trait Scanner extends Iterator[String] with AutoCloseable {
  def lineNumber(): Int

  def next[A](implicit scan: Scannable[A]): A = scan(this)

  def nextLine(): String

  def lines: Iterator[String] = Iterator.continually(nextLine()).withHasNext(hasNext)
}

/**
  * Faster, safer and more idiomatic Scala replacement for java.util.Scanner
  * See: http://codeforces.com/blog/entry/7018
  */
object Scanner {

  def apply(str: String): Scanner =
    Scanner(str, StringSplitter.default)

  def apply(str: String, splitter: StringSplitter): Scanner =
    Scanner(new StringReader(str), splitter)

  def apply(reader: Reader): Scanner =
    Scanner(reader, StringSplitter.default)

  def apply(reader: Reader, splitter: StringSplitter): Scanner =
    Scanner(new LineNumberReader(reader.buffered), splitter)

  def apply(inputStream: InputStream)(implicit charset: Charset = defaultCharset): Scanner =
    Scanner(inputStream, StringSplitter.default)(charset)

  def apply(inputStream: InputStream, splitter: StringSplitter)(implicit charset: Charset): Scanner =
    Scanner(inputStream.reader(charset), splitter)

  def apply(reader: LineNumberReader, splitter: StringSplitter): Scanner = new Scanner {
    private[this] val tokens = reader.tokens(splitter)
    override def lineNumber() = reader.getLineNumber
    override def nextLine() = reader.readLine()
    override def next() = tokens.next()
    override def hasNext = tokens.hasNext
    override def close() = reader.close()
  }

  val stdin: Scanner = Scanner(System.in)

  trait Read[A] {     // TODO: Move to own subproject when this is fixed https://github.com/typelevel/cats/issues/932
    def apply(s: String): A
  }

  object Read {
    def apply[A](f: String => A): Read[A] = new Read[A] {
      override def apply(s: String) = f(s)
    }
    implicit val string           : Read[String]            = Read(identity)
    implicit val boolean          : Read[Boolean]           = Read(_.toBoolean)
    implicit val byte             : Read[Byte]              = Read(_.toByte)  //TODO: https://issues.scala-lang.org/browse/SI-9706
    implicit val short            : Read[Short]             = Read(_.toShort)
    implicit val int              : Read[Int]               = Read(_.toInt)
    implicit val long             : Read[Long]              = Read(_.toLong)
    implicit val bigInt           : Read[BigInt]            = Read(BigInt(_))
    implicit val float            : Read[Float]             = Read(_.toFloat)
    implicit val double           : Read[Double]            = Read(_.toDouble)
    implicit val bigDecimal       : Read[BigDecimal]        = Read(BigDecimal(_))
    implicit def option[A: Read]  : Read[Option[A]]         = Read(s => when(s.nonEmpty)(implicitly[Read[A]].apply(s)))

    // Java's time readers
    import java.time._
    import java.sql.{Date => SqlDate, Time => SqlTime, Timestamp => SqlTimestamp}

    implicit val duration         : Read[Duration]          = Read(Duration.parse(_))
    implicit val instant          : Read[Instant]           = Read(Instant.parse(_))
    implicit val localDateTime    : Read[LocalDateTime]     = Read(LocalDateTime.parse(_))
    implicit val localDate        : Read[LocalDate]         = Read(LocalDate.parse(_))
    implicit val monthDay         : Read[MonthDay]          = Read(MonthDay.parse(_))
    implicit val offsetDateTime   : Read[OffsetDateTime]    = Read(OffsetDateTime.parse(_))
    implicit val offsetTime       : Read[OffsetTime]        = Read(OffsetTime.parse(_))
    implicit val period           : Read[Period]            = Read(Period.parse(_))
    implicit val year             : Read[Year]              = Read(Year.parse(_))
    implicit val yearMonth        : Read[YearMonth]         = Read(YearMonth.parse(_))
    implicit val zonedDateTime    : Read[ZonedDateTime]     = Read(ZonedDateTime.parse(_))
    implicit val sqlDate          : Read[SqlDate]           = Read(SqlDate.valueOf)
    implicit val sqlTime          : Read[SqlTime]           = Read(SqlTime.valueOf)
    implicit val sqlTimestamp     : Read[SqlTimestamp]      = Read(SqlTimestamp.valueOf)

    /**
      * Use this to create custom readers e.g. to read a LocalDate using some custom format
      * val readLocalDate: Read[LocalDate] = Read.temporalQuery(format = myFormat, query = LocalDate.from)
      * @param format
      * @param query
      * @tparam A
      * @return
      */
    def temporalQuery[A](format: DateTimeFormatter, query: temporal.TemporalQuery[A]): Read[A] =
      Read(format.parse(_, query))
  }
}

/**
  * Implement this trait to make thing parsable
  * In most cases, use Scanner.Read typeclass when you simply need access to one String token
  * Use Scannable typeclass if you need access to the full scanner e.g. to detect encodings etc.
  */
trait Scannable[A] {
  def apply(scanner: Scanner): A
}

object Scannable {
  def apply[A](f: Scanner => A): Scannable[A] = new Scannable[A] {
    override def apply(scanner: Scanner) = f(scanner)
  }

  implicit def fromRead[A](implicit read: Scanner.Read[A]): Scannable[A] =
    Scannable(s => read(s.next()))

  implicit def tuple2[T1, T2](implicit t1: Scannable[T1], t2: Scannable[T2]): Scannable[(T1, T2)] =
    Scannable(s => t1(s) -> t2(s))

  implicit def iterator[A](implicit scanner: Scannable[A]): Scannable[Iterator[A]] =
    Scannable(s => Iterator.continually(scanner(s)).withHasNext(s.hasNext))
}

trait StringSplitter {
  def split(s: String): TraversableOnce[String]
}
object StringSplitter {
  val default = StringSplitter.anyOf(" \t\t\n\r")

  /**
    * Split string on this character
    * This  will return exactly 1 + n number of items where n is the number of occurence of delimiter in String s
    *
    * @param delimiter
    * @return
    */
  def on(delimiter: Char): StringSplitter = new StringSplitter {
    override def split(s: String) = new Iterator[String] {
      private[this] var i = 0
      private[this] var j = -1
      private[this] val c = delimiter.toInt
      _next()

      private[this] def _next() = {
        i = j + 1
        val k = s.indexOf(c, i)
        j = if (k < 0) s.length else k
      }

      override def hasNext = i <= s.length

      override def next() = {
        val res = s.substring(i, j)
        _next()
        res
      }
    }
  }

  /**
    * Split this string using ANY of the characters from delimiters
    *
    * @param delimiters
    * @param includeDelimiters
    * @return
    */
  def anyOf(delimiters: String, includeDelimiters: Boolean = false): StringSplitter =
    s => new StringTokenizer(s, delimiters, includeDelimiters)

  /**
    * Split string using a regex pattern
    *
    * @param pattern
    * @return
    */
  def regex(pattern: String): StringSplitter =
    s => s.split(pattern, -1)
}
