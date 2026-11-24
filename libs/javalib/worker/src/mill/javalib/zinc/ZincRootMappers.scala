package mill.javalib.zinc

import xsbti.{FileConverter, VirtualFileRef}
import xsbti.compile.MiniSetup
import xsbti.compile.analysis.{ReadMapper, ReadWriteMappers, Stamp, WriteMapper}

import java.nio.file.{Path, Paths}
import java.util.regex.{Matcher, Pattern}

/**
 * Mappers for Zinc's analysis file. Its file ids are already relative to Mill's roots (see
 * [[ZincWorker.fileConverter]]), but the classpath and compiler options of the stored setup are
 * plain paths: these mappers write the ones under a root as `${MILL_ROOT_<KEY>}/...` and resolve
 * them against the current roots when reading, so the setup still matches in a moved or copied
 * checkout. File ids an older Mill wrote as absolute paths are read as current ids.
 */
private[zinc] object ZincRootMappers {

  /**
   * @param roots `(key, absolute path)` pairs, as provided by `mill.api.MappedRoots`.
   * @param converter the file converter built from the same roots, see [[ZincWorker.fileConverter]].
   */
  def apply(roots: Seq[(String, String)], converter: FileConverter): ReadWriteMappers =
    if (roots.isEmpty) ReadWriteMappers.getEmptyMappers
    else {
      val codec = Codec(roots)
      new ReadWriteMappers(readMapper(codec, converter), writeMapper(codec))
    }

  private[zinc] final case class Codec(roots0: Seq[(String, String)]) {
    // Most specific first
    private val roots = roots0.sortBy { case (_, root) => -root.length }

    // A root matches only as a whole path, between the string's ends, separators or delimiters
    // used in options (`:`, `;`, `,`, `=`, quotes, space): `/a/ws` matches neither `/a/ws2` nor
    // `/b/a/ws`
    private val encoders = roots.map { (key, root) =>
      (
        Pattern.compile(
          """(?<![^/\\:;,=\s"'])""" + Pattern.quote(root) + """(?=$|[/\\:;,=\s"'])"""
        ),
        Matcher.quoteReplacement(token(key))
      )
    }
    private val decoders = roots.map { (key, root) =>
      (Pattern.compile(Pattern.quote(token(key))), Matcher.quoteReplacement(root))
    }

    // Distinctive, so that a user's literal `${HOME}` in an option is never decoded
    private def token(key: String) = "${MILL_ROOT_" + key + "}"

    def encode(s: String): String =
      encoders.foldLeft(s) { case (acc, (p, r)) => p.matcher(acc).replaceAll(r) }

    def decode(s: String): String =
      if (!s.contains("${")) s
      else decoders.foldLeft(s) { case (acc, (p, r)) => p.matcher(acc).replaceAll(r) }

    def encodePath(p: Path): Path = mapPath(p, encode)
    def decodePath(p: Path): Path = mapPath(p, decode)

    // With `/`, as in the roots: `os.Path` strings use it also on Windows
    private def mapPath(p: Path, f: String => String): Path = {
      val s = p.toString.replace(p.getFileSystem.getSeparator, "/")
      val mapped = f(s)
      if (mapped == s) p else Paths.get(mapped)
    }
  }

  private def writeMapper(c: Codec): WriteMapper = new WriteMapper {
    def mapSourceFile(f: VirtualFileRef): VirtualFileRef = f
    def mapBinaryFile(f: VirtualFileRef): VirtualFileRef = f
    def mapProductFile(f: VirtualFileRef): VirtualFileRef = f
    def mapOutputDir(p: Path): Path = c.encodePath(p)
    def mapSourceDir(p: Path): Path = c.encodePath(p)
    def mapClasspathEntry(p: Path): Path = c.encodePath(p)
    def mapJavacOption(o: String): String = c.encode(o)
    def mapScalacOption(o: String): String = c.encode(o)
    def mapBinaryStamp(f: VirtualFileRef, s: Stamp): Stamp = s
    def mapSourceStamp(f: VirtualFileRef, s: Stamp): Stamp = s
    def mapProductStamp(f: VirtualFileRef, s: Stamp): Stamp = s
    // Zinc's ConsistentAnalysisFormat doesn't store the output and source folders, so it never
    // calls mapOutputDir and mapSourceDir
    def mapMiniSetup(s: MiniSetup): MiniSetup = s
  }

  private def readMapper(c: Codec, converter: FileConverter): ReadMapper = new ReadMapper {
    // Ids from an older Mill are absolute paths: unless converted, Zinc would neither prune nor
    // delete what they list, and would compile against stale class files
    private def asCurrentId(f: VirtualFileRef) =
      if (f.id.startsWith("${")) f else converter.toVirtualFileRef(Paths.get(f.id))
    def mapSourceFile(f: VirtualFileRef): VirtualFileRef = asCurrentId(f)
    def mapBinaryFile(f: VirtualFileRef): VirtualFileRef = asCurrentId(f)
    def mapProductFile(f: VirtualFileRef): VirtualFileRef = asCurrentId(f)
    def mapOutputDir(p: Path): Path = c.decodePath(p)
    def mapSourceDir(p: Path): Path = c.decodePath(p)
    def mapClasspathEntry(p: Path): Path = c.decodePath(p)
    def mapJavacOption(o: String): String = c.decode(o)
    def mapScalacOption(o: String): String = c.decode(o)
    def mapBinaryStamp(f: VirtualFileRef, s: Stamp): Stamp = s
    def mapSourceStamp(f: VirtualFileRef, s: Stamp): Stamp = s
    def mapProductStamp(f: VirtualFileRef, s: Stamp): Stamp = s
    def mapMiniSetup(s: MiniSetup): MiniSetup = s
  }
}
