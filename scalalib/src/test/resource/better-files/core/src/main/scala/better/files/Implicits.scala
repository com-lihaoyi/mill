package better.files

import java.io.{File => JFile, _}
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.{Path, PathMatcher}
import java.security.MessageDigest
import java.util.StringTokenizer
import java.util.stream.{Stream => JStream}
import java.util.zip._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Container for various implicits
  */
trait Implicits {

  //TODO: Rename all Ops to Extensions

  implicit class StringInterpolations(sc: StringContext) {
    def file(args: Any*): File =
      value(args).toFile

    private[this] def value(args: Seq[Any]) =
      sc.s(args: _*)
  }

  implicit class StringOps(str: String) {
    def toFile: File =
      File(str)

    def /(child: String): File =
      toFile / child
  }

  implicit class FileOps(file: JFile) {
    def toScala: File =
      File(file.getPath)
  }

  implicit class SymbolExtensions(symbol: Symbol) {
    def /(child: Symbol): File =
      File(symbol.name) / child
  }

  implicit class IteratorExtensions[A](it: Iterator[A]) {
    def withHasNext(f: => Boolean): Iterator[A] = new Iterator[A] {
      override def hasNext = f && it.hasNext
      override def next() = it.next()
    }
  }

  implicit class InputStreamOps(in: InputStream) {
    def pipeTo(out: OutputStream, bufferSize: Int = defaultBufferSize): out.type =
      pipeTo(out, Array.ofDim[Byte](bufferSize))

    /**
      * Pipe an input stream to an output stream using a byte buffer
      */
    @tailrec final def pipeTo(out: OutputStream, buffer: Array[Byte]): out.type = {
      val n = in.read(buffer)
      if (n > 0) {
        out.write(buffer, 0, n)
        pipeTo(out, buffer)
      } else {
        out
      }
    }

    def asString(closeStream: Boolean = true, bufferSize: Int = defaultBufferSize)(implicit charset: Charset = defaultCharset): String = {
      try {
        new ByteArrayOutputStream(bufferSize).autoClosed
          .map(pipeTo(_, bufferSize = bufferSize).toString(charset.displayName()))
      } finally {
        if (closeStream) in.close()
      }
    }

    def buffered: BufferedInputStream =
      new BufferedInputStream(in)

    def buffered(bufferSize: Int): BufferedInputStream =
      new BufferedInputStream(in, bufferSize)

    def gzipped: GZIPInputStream =
      new GZIPInputStream(in)

    /**
      * If bufferSize is set to less than or equal to 0, we don't buffer
      * @param bufferSize
      * @return
      */
    def asObjectInputStream(bufferSize: Int = defaultBufferSize): ObjectInputStream =
      new ObjectInputStream(if (bufferSize <= 0) in else buffered(bufferSize))

    /**
      * @param bufferSize If bufferSize is set to less than or equal to 0, we don't buffer
      * Code adapted from:
      * https://github.com/apache/commons-io/blob/master/src/main/java/org/apache/commons/io/input/ClassLoaderObjectInputStream.java
      *
      * @return A special ObjectInputStream that loads a class based on a specified ClassLoader rather than the default
      * This is useful in dynamic container environments.
      */
    def asObjectInputStreamUsingClassLoader(classLoader: ClassLoader = getClass.getClassLoader, bufferSize: Int = defaultBufferSize): ObjectInputStream =
      new ObjectInputStream(if (bufferSize <= 0) in else buffered(bufferSize)) {
        override protected def resolveClass(objectStreamClass: ObjectStreamClass): Class[_] =
          try {
            Class.forName(objectStreamClass.getName, false, classLoader)
          } catch {
            case _: ClassNotFoundException ⇒ super.resolveClass(objectStreamClass)
          }

        override protected def resolveProxyClass(interfaces: Array[String]): Class[_] = {
          try {
            java.lang.reflect.Proxy.getProxyClass(
              classLoader,
              interfaces.map(interface => Class.forName(interface, false, classLoader)) : _*
            )
          } catch {
            case _: ClassNotFoundException | _: IllegalArgumentException => super.resolveProxyClass(interfaces)
          }
        }
      }

    def reader(implicit charset: Charset = defaultCharset): InputStreamReader =
      new InputStreamReader(in, charset)

    def lines(implicit charset: Charset = defaultCharset): Iterator[String] =
      reader(charset).buffered.lines().toAutoClosedIterator

    def bytes: Iterator[Byte] =
      in.autoClosed.flatMap(res => eofReader(res.read()).map(_.toByte))
  }

  implicit class OutputStreamOps(val out: OutputStream) {
    def buffered: BufferedOutputStream =
      new BufferedOutputStream(out)

    def buffered(bufferSize: Int): BufferedOutputStream =
      new BufferedOutputStream(out, bufferSize)

    def gzipped: GZIPOutputStream =
      new GZIPOutputStream(out)

    def writer(implicit charset: Charset = defaultCharset): OutputStreamWriter =
      new OutputStreamWriter(out, charset)

    def printWriter(autoFlush: Boolean = false): PrintWriter =
      new PrintWriter(out, autoFlush)

    def write(bytes: Iterator[Byte], bufferSize: Int = defaultBufferSize): out.type = {
      bytes.grouped(bufferSize).foreach(buffer => out.write(buffer.toArray))
      out.flush()
      out
    }

    def tee(out2: OutputStream): OutputStream =
      new TeeOutputStream(out, out2)

    /**
      * If bufferSize is set to less than or equal to 0, we don't buffer
      * @param bufferSize
      * @return
      */
    def asObjectOutputStream(bufferSize: Int = defaultBufferSize): ObjectOutputStream =
      new ObjectOutputStream(if (bufferSize <= 0) out else buffered(bufferSize))
  }

  implicit class ReaderOps(reader: Reader) {
    def buffered: BufferedReader =
      new BufferedReader(reader)

    def toInputStream(implicit charset: Charset = defaultCharset): InputStream =
      new ReaderInputStream(reader)(charset)
  }

  implicit class BufferedReaderOps(reader: BufferedReader) {
    def chars: Iterator[Char] =
      reader.autoClosed.flatMap(res => eofReader(res.read()).map(_.toChar))

    def tokens(splitter: StringSplitter = StringSplitter.default): Iterator[String] =
      reader.lines().toAutoClosedIterator.flatMap(splitter.split)
  }

  implicit class WriterOps(writer: Writer) {
    def buffered: BufferedWriter =
      new BufferedWriter(writer)

    def outputstream(implicit charset: Charset = defaultCharset): OutputStream =
      new WriterOutputStream(writer)(charset)
  }

  implicit class FileChannelOps(fc: FileChannel) {
    def toMappedByteBuffer: MappedByteBuffer =
      fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size())
  }

  implicit class PathMatcherOps(matcher: PathMatcher) {
    def matches(file: File)(implicit visitOptions: File.VisitOptions = File.VisitOptions.default) =
      file.collectChildren(child => matcher.matches(child.path))(visitOptions)
  }

  implicit class ObjectInputStreamOps(ois: ObjectInputStream) {
    def deserialize[A]: A =
      ois.readObject().asInstanceOf[A]
  }

  implicit class ObjectOutputStreamOps(val oos: ObjectOutputStream) {
    def serialize(obj: Serializable): oos.type = {
      oos.writeObject(obj)
      oos
    }
  }

  implicit class ZipOutputStreamOps(val out: ZipOutputStream) {

    /**
      * Correctly set the compression level
      * See: http://stackoverflow.com/questions/1206970/creating-zip-using-zip-utility
      *
      * @param level
      * @return
      */
    def withCompressionLevel(level: Int): out.type = {
      out.setLevel(level)
      if (level == Deflater.NO_COMPRESSION) out.setMethod(ZipOutputStream.DEFLATED)
      out
    }

    def add(file: File, name: String): out.type = {
      val relativeName = name.stripSuffix(file.fileSystem.getSeparator)
      val entryName = if (file.isDirectory) s"$relativeName/" else relativeName // make sure to end directories in ZipEntry with "/"
      out.putNextEntry(new ZipEntry(entryName))
      if (file.isRegularFile) file.inputStream.foreach(_.pipeTo(out))
      out.closeEntry()
      out
    }

    def +=(file: File): out.type =
      add(file, file.name)
  }

  implicit class ZipInputStreamOps(val in: ZipInputStream) {
    def mapEntries[A](f: ZipEntry => A): Iterator[A] = new Iterator[A] {
      private[this] var entry = in.getNextEntry

      override def hasNext = entry != null

      override def next() = {
        val result = Try(f(entry))
        Try(in.closeEntry())
        entry = in.getNextEntry
        result.get
      }
    }
  }

  implicit class ZipEntryOps(val entry: ZipEntry) {
    /**
      * Extract this ZipEntry under this rootDir
      *
      * @param rootDir directory under which this entry is extracted
      * @param inputStream use this inputStream when this entry is a file
      * @return the extracted file
      */
    def extractTo(rootDir: File, inputStream: => InputStream): File = {
      val child = rootDir.createChild(entry.getName, asDirectory = entry.isDirectory, createParents = true)
      if (!entry.isDirectory) child.outputStream.foreach(inputStream.pipeTo(_))
      child
    }
  }

  implicit class CloseableOps[A <: AutoCloseable](resource: A) {
    /**
      * Lightweight automatic resource management
      * Closes the resource when done e.g.
      * <pre>
      * for {
      * in <- file.newInputStream.autoClosed
      * } in.write(bytes)
      * // in is closed now
      * </pre>
      *
      * @return
      */
    def autoClosed: ManagedResource[A] =
      new ManagedResource(resource)(Disposable.closableDisposer)
  }

  implicit class JStreamOps[A](stream: JStream[A]) {
    /**
      * Closes this stream when iteration is complete
      * It will NOT close the stream if it is not depleted!
      *
      * @return
      */
    def toAutoClosedIterator: Iterator[A] =
      stream.autoClosed.flatMap(_.iterator().asScala)
  }

  private[files] implicit class OrderingOps[A](order: Ordering[A]) {
    def andThenBy(order2: Ordering[A]): Ordering[A] =
      Ordering.comparatorToOrdering(order.thenComparing(order2))
  }

  implicit def stringToMessageDigest(algorithmName: String): MessageDigest =
    MessageDigest.getInstance(algorithmName)

  implicit def stringToCharset(charsetName: String): Charset =
    Charset.forName(charsetName)

  implicit def tokenizerToIterator(s: StringTokenizer): Iterator[String] =
    Iterator.continually(s.nextToken()).withHasNext(s.hasMoreTokens)

  //implicit def posixPermissionToFileAttribute(perm: PosixFilePermission) =
  //  PosixFilePermissions.asFileAttribute(Set(perm))

  private[files] implicit def pathStreamToFiles(files: JStream[Path]): Files =
    files.toAutoClosedIterator.map(File.apply)
}
