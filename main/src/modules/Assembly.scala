package mill.modules

import java.io.InputStream
import java.util.jar.JarFile
import java.util.regex.Pattern
import mill.Agg
import os.Generator
import scala.collection.JavaConverters._

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
      def apply(pattern: String): AppendPattern = AppendPattern(Pattern.compile(pattern))
    }
    case class AppendPattern(pattern: Pattern) extends Rule

    case class Exclude(path: String) extends Rule

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
      case r@Rule.Append(path, _) => path -> r
      case r@Rule.Exclude(path) => path -> r
    }.toMap

    val appendPatterns = assemblyRules.collect {
      case Rule.AppendPattern(pattern) => pattern.asPredicate().test(_)
    }

    val excludePatterns = assemblyRules.collect {
      case Rule.ExcludePattern(pattern) => pattern.asPredicate().test(_)
    }

    mappings.foldLeft(Map.empty[String, GroupedEntry]) {
      case (entries, (mapping, entry)) =>
        rulesMap.get(mapping) match {
          case Some(_: Assembly.Rule.Exclude) =>
            entries
          case Some(a: Assembly.Rule.Append) =>
            val newEntry = entries.getOrElse(mapping, AppendEntry(Nil, a.separator)).append(entry)
            entries + (mapping -> newEntry)
          case _ if excludePatterns.exists(_(mapping)) =>
            entries
          case _ if appendPatterns.exists(_(mapping)) =>
            val newEntry = entries.getOrElse(mapping, AppendEntry.empty).append(entry)
            entries + (mapping -> newEntry)
          case _ if !entries.contains(mapping) =>
            entries + (mapping -> WriteOnceEntry(entry))
          case _ =>
            entries
        }
    }
  }

  def loadClasspath(
    inputPaths: Agg[os.Path]
  ): Generator[(String, UnopenedInputStream)] = {
    Generator.from(inputPaths).filter(os.exists).flatMap { path =>
      if (os.isFile(path)) {
        val jarFile = new JarFile(path.toIO)
        Generator.from(jarFile.entries().asScala.filterNot(_.isDirectory))
          .map(entry => entry.getName -> (() => jarFile.getInputStream(entry)))
      }
      else {
        os.walk
          .stream(path)
          .filter(os.isFile)
          .map(subPath => subPath.relativeTo(path).toString -> (() => os.read.inputStream(subPath)))
      }
    }
  }

  type UnopenedInputStream = () => InputStream

  private[modules] sealed trait GroupedEntry {
    def append(entry: UnopenedInputStream): GroupedEntry
  }
  private[modules] object AppendEntry {
    val empty: AppendEntry = AppendEntry(Nil, defaultSeparator)
  }
  private[modules] case class AppendEntry(inputStreams: Seq[UnopenedInputStream], separator: String) extends GroupedEntry {
    def append(inputStream: UnopenedInputStream): GroupedEntry = copy(inputStreams = inputStreams :+ inputStream)
  }
  private[modules] case class WriteOnceEntry(inputStream: UnopenedInputStream) extends GroupedEntry {
    def append(entry: UnopenedInputStream): GroupedEntry = this
  }
}
