package mill.internal

import java.nio.file.{Files, LinkOption}

object MillPathSerializer {
  def setupSymlinks(wd: os.Path, workspace: os.Path): Unit = {
    for ((base, link) <- defaultMapping(workspace)) {
      val target = wd / link
      val targetNio = target.toNIO
      os.makeDir.all(target / "..")

      if (Files.isSymbolicLink(targetNio)) {
        val currentTarget = Files.readSymbolicLink(targetNio)
        if (currentTarget != base.wrapped) {
          os.remove(target)
          Files.createSymbolicLink(targetNio, base.wrapped)
        }
      } else if (Files.exists(targetNio, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException(
          s"Refusing to overwrite non-symlink path required for path serialization: $target"
        )
      } else {
        Files.createSymbolicLink(targetNio, base.wrapped)
      }
    }
  }

  def defaultMapping(workspace: os.Path): Seq[(os.Path, os.SubPath)] = Seq(
    workspace -> os.sub / "out/mill-workspace",
    os.home -> os.sub / "out/mill-home"
  )
}

class MillPathSerializer(mapping: Seq[(os.Path, os.SubPath)]) extends os.Path.Serializer {

  private def mapPathPrefixes(path: os.Path, mapping: Seq[(os.Path, os.SubPath)]): os.FilePath = {
    mapping
      .collectFirst { case (from, to) if path.startsWith(from) => to / path.subRelativeTo(from) }
      .getOrElse(path)
  }

  private def mapPathPrefixes(
      path: java.nio.file.Path,
      mapping: Seq[(os.SubPath, os.Path)]
  ): java.nio.file.Path = {
    mapping
      .collectFirst {
        case (from, to) if path.startsWith(from.toNIO) || from.segments.isEmpty =>
          to.wrapped.resolve(
            // java.nio.Path#relativize misbehaves on empty paths
            if (from.segments.isEmpty) path else from.toNIO.relativize(path)
          )
      }
      .getOrElse(path)
  }

  def normalizePath(path: os.Path): os.FilePath = mapPathPrefixes(path, mapping)

  def denormalizePath(path: java.nio.file.Path): java.nio.file.Path =
    mapPathPrefixes(path, mapping.map(_.swap))

  override def serializeString(p: os.Path): String = normalizePath(p) match {
    case p: os.SubPath => p.toNIO.toString
    case p: os.Path => p.wrapped.toString
    case p: os.RelPath => p.toNIO.toString
  }

  override def serializeFile(p: os.Path): java.io.File = normalizePath(p) match {
    case p: os.SubPath => p.toNIO.toFile
    case p: os.Path => p.wrapped.toFile
    case p: os.RelPath => p.toNIO.toFile
  }

  override def serializePath(p: os.Path): java.nio.file.Path = normalizePath(p) match {
    case p: os.SubPath => p.toNIO
    case p: os.Path => p.wrapped
    case p: os.RelPath => p.toNIO
  }

  override def deserialize(s: String): java.nio.file.Path =
    denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

  override def deserialize(s: java.io.File): java.nio.file.Path =
    denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

  override def deserialize(s: java.nio.file.Path): java.nio.file.Path =
    denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

  override def deserialize(s: java.net.URI): java.nio.file.Path =
    denormalizePath(os.Path.defaultPathSerializer.deserialize(s))
}
