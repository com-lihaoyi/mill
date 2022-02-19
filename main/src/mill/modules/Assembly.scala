package mill.modules

import com.eed3si9n.jarjarabrams.{ShadePattern, Shader}
import java.io.{ByteArrayInputStream, Closeable, InputStream}
import java.util.jar.JarFile
import java.util.regex.Pattern

import mill.Agg
import os.Generator
import scala.jdk.CollectionConverters._
import scala.tools.nsc.io.Streamable

object Assembly {

  val defaultRules: Seq[Rule] = Seq(
    Rule.Append("reference.conf", separator = "\n"),
    Rule.Exclude(JarFile.MANIFEST_NAME),
    Rule.ExcludePattern(".*\\.[sS][fF]"),
    Rule.ExcludePattern(".*\\.[dD][sS][aA]"),
    Rule.ExcludePattern(".*\\.[rR][sS][aA]")
  )

  val defaultSeparator = ""

  sealed trait Rule extends Product with Serializable
  object Rule {
    case class Append(path: String, separator: String = defaultSeparator) extends Rule

    object AppendPattern {
      def apply(pattern: Pattern): AppendPattern = new AppendPattern(pattern, defaultSeparator)
      def apply(pattern: String): AppendPattern = apply(pattern, defaultSeparator)
      def apply(pattern: String, separator: String): AppendPattern =
        new AppendPattern(Pattern.compile(pattern), separator)

      @deprecated(message = "Binary compatibility shim. Don't use it. To be removed", since = "mill 0.10.1")
      def unapply(value: AppendPattern): Option[Pattern] = Some(value.pattern)
    }
    class AppendPattern(val pattern: Pattern, val separator: String) extends Rule {
      def this(pattern: Pattern) = this(pattern, defaultSeparator)

      override def productPrefix: String = "AppendPattern"
      override def productArity: Int = 2
      override def productElement(n: Int): Any = n match {
        case 0 => pattern
        case 1 => separator
        case _ => throw new IndexOutOfBoundsException(n.toString)
      }
      override def canEqual(that: Any): Boolean = that.isInstanceOf[AppendPattern]
      override def hashCode(): Int = scala.runtime.ScalaRunTime._hashCode(this)
      override def equals(obj: Any): Boolean = obj match {
        case that: AppendPattern => this.pattern == that.pattern && this.separator == that.separator
        case _ => false
      }
      override def toString: String = scala.runtime.ScalaRunTime._toString(this)

      @deprecated(message = "Binary compatibility shim. Don't use it. To be removed", since = "mill 0.10.1")
      def copy(pattern: Pattern = pattern): AppendPattern = new AppendPattern(pattern, separator)
    }

    case class Exclude(path: String) extends Rule

    case class Relocate(from: String, to: String) extends Rule

    object ExcludePattern {
      def apply(pattern: String): ExcludePattern = ExcludePattern(Pattern.compile(pattern))
    }
    case class ExcludePattern(pattern: Pattern) extends Rule
  }

  def groupAssemblyEntries(
      mappings: Generator[(String, UnopenedInputStream)],
      assemblyRules: Seq[Assembly.Rule]
  ): Map[String, GroupedEntry] = {
    val rulesMap = assemblyRules.collect {
      case r @ Rule.Append(path, _) => path -> r
      case r @ Rule.Exclude(path) => path -> r
    }.toMap

    val matchPatterns = assemblyRules.collect {
      case r : Rule.AppendPattern => r.pattern.asPredicate() -> r
      case r @ Rule.ExcludePattern(pattern) => pattern.asPredicate() -> r
    }

    mappings.foldLeft(Map.empty[String, GroupedEntry]) {
      case (entries, (mapping, entry)) =>
        def simpleRule = rulesMap.get(mapping)
        def patternRule = matchPatterns.find(_._1.test(mapping)).map(_._2)
        simpleRule orElse patternRule match {
          case Some(_: Rule.Exclude) =>
            entries
          case Some(a: Rule.Append) =>
            val newEntry = entries.getOrElse(mapping, AppendEntry(Nil, a.separator)).append(entry)
            entries + (mapping -> newEntry)
          case Some(_: Rule.ExcludePattern) =>
            entries
          case Some(a: Rule.AppendPattern) =>
            val newEntry = entries.getOrElse(mapping, AppendEntry(Nil, a.separator)).append(entry)
            entries + (mapping -> newEntry)
          case _ if !entries.contains(mapping) =>
            entries + (mapping -> WriteOnceEntry(entry))
          case _ =>
            entries
        }
    }
  }

  type ResourceCloser = () => Unit

  def loadShadedClasspath(
      inputPaths: Agg[os.Path],
      assemblyRules: Seq[Assembly.Rule]
  ): (Generator[(String, UnopenedInputStream)], ResourceCloser) = {
    val shadeRules = assemblyRules.collect {
      case Rule.Relocate(from, to) => ShadePattern.Rename(List(from -> to)).inAll
    }
    val shader =
      if (shadeRules.isEmpty)
        (name: String, inputStream: UnopenedInputStream) => Some(name -> inputStream)
      else {
        val shader = Shader.bytecodeShader(shadeRules, verbose = false)
        (name: String, inputStream: UnopenedInputStream) => {
          val is = inputStream()
          shader(Streamable.bytes(is), name).map {
            case (bytes, name) =>
              name -> (() =>
                new ByteArrayInputStream(bytes) {
                  override def close(): Unit = is.close()
                }
              )
          }
        }
      }

    val pathsWithResources = inputPaths.filter(os.exists).map { path =>
      if (os.isFile(path)) path -> Some(new JarFile(path.toIO))
      else path -> None
    }

    val generators = Generator.from(pathsWithResources).flatMap {
      case (path, Some(jarFile)) =>
        Generator.from(jarFile.entries().asScala.filterNot(_.isDirectory))
          .flatMap(entry => shader(entry.getName, () => jarFile.getInputStream(entry)))
      case (path, None) =>
        os.walk
          .stream(path)
          .filter(os.isFile)
          .flatMap(subPath =>
            shader(subPath.relativeTo(path).toString, () => os.read.inputStream(subPath))
          )
    }

    (generators, () => pathsWithResources.flatMap(_._2).iterator.foreach(_.close()))
  }

  type UnopenedInputStream = () => InputStream

  private[modules] sealed trait GroupedEntry {
    def append(entry: UnopenedInputStream): GroupedEntry
  }
  private[modules] object AppendEntry {
    val empty: AppendEntry = AppendEntry(Nil, defaultSeparator)
  }
  private[modules] case class AppendEntry(inputStreams: Seq[UnopenedInputStream], separator: String)
      extends GroupedEntry {
    def append(inputStream: UnopenedInputStream): GroupedEntry =
      copy(inputStreams = inputStreams :+ inputStream)
  }
  private[modules] case class WriteOnceEntry(inputStream: UnopenedInputStream)
      extends GroupedEntry {
    def append(entry: UnopenedInputStream): GroupedEntry = this
  }
}
