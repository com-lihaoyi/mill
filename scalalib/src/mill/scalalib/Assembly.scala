package mill.scalalib

import com.eed3si9n.jarjarabrams.{ShadePattern, Shader}
import mill.Agg
import mill.api.{Ctx, IO, PathRef}
import os.Generator

import java.io.{ByteArrayInputStream, InputStream, SequenceInputStream}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.jar.JarFile
import java.util.regex.Pattern
import scala.jdk.CollectionConverters._
import scala.util.Using

case class Assembly(pathRef: PathRef, addedEntries: Int)

object Assembly {

  private object Streamable {
    def bytes(is: InputStream): Array[Byte] = {
      val buffer = new Array[Byte](8192)
      val out = new java.io.ByteArrayOutputStream
      var read = 0
      while ({
        read = is.read(buffer)
        read != -1
      }) {
        out.write(buffer, 0, read)
      }
      out.close()
      out.toByteArray
    }
  }

  implicit val assemblyJsonRW: upickle.default.ReadWriter[Assembly] = upickle.default.macroRW

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

      @deprecated(
        message = "Binary compatibility shim. Don't use it. To be removed",
        since = "mill 0.10.1"
      )
      def unapply(value: AppendPattern): Option[Pattern] = Some(value.pattern)
    }
    class AppendPattern private (val pattern: Pattern, val separator: String) extends Rule {
      @deprecated(
        message = "Binary compatibility shim. Don't use it. To be removed",
        since = "mill 0.10.1"
      )
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

      @deprecated(
        message = "Binary compatibility shim. Don't use it. To be removed",
        since = "mill 0.10.1"
      )
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
      case r: Rule.AppendPattern => r.pattern.asPredicate() -> r
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
        val shader = Shader.bytecodeShader(shadeRules, verbose = false, skipManifest = true)
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

  private[scalalib] sealed trait GroupedEntry {
    def append(entry: UnopenedInputStream): GroupedEntry
  }
  private[scalalib] object AppendEntry {
    val empty: AppendEntry = AppendEntry(Nil, defaultSeparator)
  }
  private[scalalib] case class AppendEntry(
      inputStreams: Seq[UnopenedInputStream],
      separator: String
  ) extends GroupedEntry {
    def append(inputStream: UnopenedInputStream): GroupedEntry =
      copy(inputStreams = inputStreams :+ inputStream)
  }
  private[scalalib] case class WriteOnceEntry(inputStream: UnopenedInputStream)
      extends GroupedEntry {
    def append(entry: UnopenedInputStream): GroupedEntry = this
  }

  def createAssembly(
      inputPaths: Agg[os.Path],
      manifest: mill.api.JarManifest = mill.api.JarManifest.MillDefault,
      prependShellScript: String = "",
      base: Option[os.Path] = None,
      assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules
  )(implicit ctx: Ctx.Dest with Ctx.Log): PathRef = {
    create(
      destJar = ctx.dest / "out.jar",
      inputPaths = inputPaths,
      manifest = manifest,
      prependShellScript = Option(prependShellScript).filter(_ != ""),
      base = base,
      assemblyRules = assemblyRules
    ).pathRef
  }

  def create(
      destJar: os.Path,
      inputPaths: Agg[os.Path],
      manifest: mill.api.JarManifest = mill.api.JarManifest.MillDefault,
      prependShellScript: Option[String] = None,
      base: Option[os.Path] = None,
      assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules
  ): Assembly = {
    val rawJar = os.temp("out-tmp", deleteOnExit = false)
    // we create the file later
    os.remove(rawJar)

    // use the `base` (the upstream assembly) as a start
    base.foreach(os.copy.over(_, rawJar))

    var addedEntryCount = 0

    // Add more files by copying files to a JAR file system
    Using.resource(os.zip.open(rawJar)) { zipRoot =>
      val manifestPath = zipRoot / os.SubPath(JarFile.MANIFEST_NAME)
      os.makeDir.all(manifestPath / os.up)
      Using.resource(os.write.outputStream(
        manifestPath,
        openOptions = Seq(
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.CREATE
        )
      )) { manifestOut =>
        manifest.build.write(manifestOut)
      }

      val (mappings, resourceCleaner) = Assembly.loadShadedClasspath(inputPaths, assemblyRules)
      try {
        Assembly.groupAssemblyEntries(mappings, assemblyRules).foreach {
          case (mapping, entry) =>
            val path = zipRoot / os.SubPath(mapping)
            entry match {
              case entry: AppendEntry =>
                val separated = entry.inputStreams
                  .flatMap(inputStream =>
                    Seq(new ByteArrayInputStream(entry.separator.getBytes), inputStream())
                  )
                val cleaned = if (os.exists(path)) separated else separated.drop(1)
                Using.resource(new SequenceInputStream(Collections.enumeration(cleaned.asJava))) {
                  concatenated =>
                    addedEntryCount += 1
                    writeEntry(path, concatenated, append = true)
                }
              case entry: WriteOnceEntry =>
                addedEntryCount += 1
                Using.resource(entry.inputStream()) { stream =>
                  writeEntry(path, stream, append = false)
                }
            }
        }
      } finally {
        resourceCleaner()
      }
    }

    // Prepend shell script and make it executable
    prependShellScript match {
      case None =>
        os.move(rawJar, destJar)
      case Some(prependShellScript) =>
        val lineSep = if (!prependShellScript.endsWith("\n")) "\n\r\n" else ""
        val prepend = prependShellScript + lineSep
        // Write the prepend-part into the final jar file
        // https://en.wikipedia.org/wiki/Zip_(file_format)#Combination_with_other_file_formats
        os.write(destJar, prepend)
        // Append the actual JAR content
        Using.resource(os.read.inputStream(rawJar)) { is =>
          os.write.append(destJar, is)
        }
        os.remove(rawJar)

        if (!scala.util.Properties.isWin) {
          os.perms.set(
            destJar,
            os.perms(destJar)
              + PosixFilePermission.GROUP_EXECUTE
              + PosixFilePermission.OWNER_EXECUTE
              + PosixFilePermission.OTHERS_EXECUTE
          )
        }
    }

    Assembly(PathRef(destJar), addedEntryCount)
  }

  private def writeEntry(p: os.Path, inputStream: InputStream, append: Boolean): Unit = {
    if (p.segmentCount != 0) os.makeDir.all(p / os.up)
    val options =
      if (append) Seq(StandardOpenOption.APPEND, StandardOpenOption.CREATE)
      else Seq(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)

    Using.resource(os.write.outputStream(p, openOptions = options)) { outputStream =>
      IO.stream(inputStream, outputStream)
    }
  }
}
