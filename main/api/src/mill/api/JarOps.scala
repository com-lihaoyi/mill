package mill.api

import java.io.{BufferedOutputStream, FileOutputStream}
import java.util.jar.{Attributes, JarEntry, JarOutputStream, Manifest}
import scala.collection.mutable
import mill.api.Loose.Agg

@experimental
object JarOps {

  /**
   * Create a JAR file with default inflation level.
   * d
   * @param jar The final JAR file
   * @param inputPaths The input paths resembling the content of the JAR file.
   *     Files will be directly included in the root of the archive,
   *     whereas for directories their content is added to the root of the archive.
   * @param manifest The JAR Manifest
   * @param fileFilter A filter to support exclusions of selected files
   * @param includeDirs If `true` the JAR archive will contain directory entries.
   *                    According to the ZIP specification, directory entries are not required.
   *                    In the Java ecosystem, most JARs have directory entries, so including them may reduce compatibility issues.
   *                    Directory entry names will result with a trailing `/`.
   * @param timestamp If specified, this timestamp is used as modification timestamp (mtime) for all entries in the JAR file.
   *                  Having a stable timestamp may result in reproducible files, if all other content, including the JAR Manifest, keep stable.
   */
  def jar(
      jar: os.Path,
      inputPaths: Agg[os.Path],
      manifest: Manifest = JarManifest().build,
      fileFilter: (os.Path, os.RelPath) => Boolean = (_, _) => true,
      includeDirs: Boolean = false,
      timestamp: Option[Long] = None
  ): Unit = {

    val curTime = timestamp.getOrElse(System.currentTimeMillis())
    def mTime(file: os.Path) = timestamp.getOrElse(os.mtime(file))

    os.makeDir.all(jar / os.up)
    os.remove.all(jar)

    val seen = mutable.Set.empty[os.RelPath]
    seen.add(os.sub / "META-INF" / "MANIFEST.MF")
    seen.add(os.sub)

    val jarStream = new JarOutputStream(
      new BufferedOutputStream(new FileOutputStream(jar.toIO)),
      manifest
    )

    try {
      assert(inputPaths.iterator.forall(os.exists(_)))

      if (includeDirs) {
        val entry = new JarEntry("META-INF/")
        entry.setTime(curTime)
        jarStream.putNextEntry(entry)
        jarStream.closeEntry()
      }

      // Note: we only sort each input path, but not the whole archive
      for {
        p <- inputPaths
        (file, mapping) <-
          if (os.isFile(p)) Seq((p, os.sub / p.last))
          else os.walk(p).map(sub => (sub, sub.subRelativeTo(p))).sorted
        if (includeDirs || os.isFile(file)) && !seen(mapping) && fileFilter(p, mapping)
      } {
        seen.add(mapping)
        val name = mapping.toString() + (if (os.isDir(file)) "/" else "")
        val entry = new JarEntry(name)
        entry.setTime(mTime(file))
        jarStream.putNextEntry(entry)
        if (os.isFile(file)) jarStream.write(os.read.bytes(file))
        jarStream.closeEntry()
      }
    } finally {
      jarStream.close()
    }
  }

  /**
   * Represents a JAR manifest.
   *
   * @param main   the main manifest attributes
   * @param groups additional attributes for named entries
   */
  final case class JarManifest private (
      main: Map[String, String],
      groups: Map[String, Map[String, String]]
  ) {
    def add(entries: (String, String)*): JarManifest = copy(main = main ++ entries)

    def addGroup(group: String, entries: (String, String)*): JarManifest =
      copy(groups = groups + (group -> (groups.getOrElse(group, Map.empty) ++ entries)))

    private def copy(
        main: Map[String, String] = main,
        groups: Map[String, Map[String, String]] = groups
    ): JarManifest = new JarManifest(main, groups)

    /** Constructs a [[java.util.jar.Manifest]] from this JarManifest. */
    def build: Manifest = {
      val manifest = new Manifest
      val mainAttributes = manifest.getMainAttributes
      main.foreach { case (key, value) => mainAttributes.putValue(key, value) }
      val entries = manifest.getEntries
      for ((group, attribs) <- groups) {
        val attrib = new Attributes
        attribs.foreach { case (key, value) => attrib.putValue(key, value) }
        entries.put(group, attrib)
      }
      manifest
    }
  }

  object JarManifest {
    implicit val jarManifestRW: upickle.default.ReadWriter[JarManifest] = upickle.default.macroRW

    def apply(
        main: Map[String, String] = Map.empty,
        groups: Map[String, Map[String, String]] = Map.empty
    ): JarManifest = new JarManifest(main, groups)

    private def unapply(jarManifest: JarManifest)
        : Option[(Map[String, String], Map[String, Map[String, String]])] =
      Some(jarManifest.main, jarManifest.groups)
  }
}
