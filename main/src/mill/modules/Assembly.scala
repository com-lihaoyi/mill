package mill.modules

import java.io.InputStream
import java.util.jar.JarFile
import java.util.regex.Pattern

import geny.Generator
import mill.Agg

import scala.collection.JavaConverters._

object Assembly {

  val defaultRules: Seq[Rule] = Seq(
    Rule.Append("reference.conf"),
    Rule.Exclude(JarFile.MANIFEST_NAME),
    Rule.ExcludePattern(".*\\.[sS][fF]"),
    Rule.ExcludePattern(".*\\.[dD][sS][aA]"),
    Rule.ExcludePattern(".*\\.[rR][sS][aA]")
  )

  sealed trait Rule extends Product with Serializable
  object Rule {
    case class Append(path: String) extends Rule

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

  def groupAssemblyEntries(inputPaths: Agg[os.Path], assemblyRules: Seq[Assembly.Rule]): Map[String, GroupedEntry] = {
    val rulesMap = assemblyRules.collect {
      case r@Rule.Append(path) => path -> r
      case r@Rule.Exclude(path) => path -> r
    }.toMap

    val appendPatterns = assemblyRules.collect {
      case Rule.AppendPattern(pattern) => pattern.asPredicate().test(_)
    }

    val excludePatterns = assemblyRules.collect {
      case Rule.ExcludePattern(pattern) => pattern.asPredicate().test(_)
    }

    classpathIterator(inputPaths).foldLeft(Map.empty[String, GroupedEntry]) {
      case (entries, entry) =>
        val mapping = entry.mapping

        rulesMap.get(mapping) match {
          case Some(_: Assembly.Rule.Exclude) =>
            entries
          case Some(_: Assembly.Rule.Append) =>
            val newEntry = entries.getOrElse(mapping, AppendEntry.empty).append(entry)
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

  private def classpathIterator(inputPaths: Agg[os.Path]): Generator[AssemblyEntry] = {
    Generator.from(inputPaths)
      .filter(os.exists)
      .flatMap {
        p =>
          if (os.isFile(p)) {
            val jf = new JarFile(p.toIO)
            Generator.from(
              for(entry <- jf.entries().asScala if !entry.isDirectory)
                yield JarFileEntry(entry.getName, () => jf.getInputStream(entry))
            )
          }
          else {
            os.walk.stream(p)
              .filter(os.isFile)
              .map(sub => PathEntry(sub.relativeTo(p).toString, sub))
          }
      }
  }
}

private[modules] sealed trait GroupedEntry {
  def append(entry: AssemblyEntry): GroupedEntry
}

private[modules] object AppendEntry {
  val empty: AppendEntry = AppendEntry(Nil)
}

private[modules] case class AppendEntry(entries: List[AssemblyEntry]) extends GroupedEntry {
  def append(entry: AssemblyEntry): GroupedEntry = copy(entries = entry :: this.entries)
}

private[modules] case class WriteOnceEntry(entry: AssemblyEntry) extends GroupedEntry {
  def append(entry: AssemblyEntry): GroupedEntry = this
}

private[this] sealed trait AssemblyEntry {
  def mapping: String
  def inputStream: InputStream
}

private[this] case class PathEntry(mapping: String, path: os.Path) extends AssemblyEntry {
  def inputStream: InputStream = os.read.inputStream(path)
}

private[this] case class JarFileEntry(mapping: String, getIs: () => InputStream) extends AssemblyEntry {
  def inputStream: InputStream = getIs()
}
