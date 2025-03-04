package mill.internal

import java.nio.file.Files

object MillPathSerializer {
  def setupSymlinks(wd: os.Path, workspace: os.Path) = {

    for ((base, link) <- defaultMapping(workspace)) {
      os.makeDir.all(wd / link / "..")
      os.remove(wd / link)
      Files.createSymbolicLink((wd / link).toNIO, base.wrapped)
    }
  }

  def defaultMapping(workspace: os.Path): Seq[(os.Path, os.SubPath)] = Seq(
    workspace -> os.sub / "out/mill-workspace",
    os.home -> os.sub / "out/mill-home"
  )
}
class MillPathSerializer(mapping: Seq[(os.Path, os.SubPath)])
    extends os.Path.Serializer {

  def mapPathPrefixes(path: os.Path, mapping: Seq[(os.Path, os.SubPath)]): os.FilePath = {
    mapping
      .collectFirst { case (from, to) if path.startsWith(from) => to / path.subRelativeTo(from) }
      .getOrElse(path)
  }

  def mapPathPrefixes2(
      path: java.nio.file.Path,
      mapping: Seq[(os.SubPath, os.Path)]
  ): java.nio.file.Path = {
    mapping
      .collectFirst {
        case (from, to) if path.startsWith(from.toNIO) || from.segments.isEmpty =>
          to.wrapped.resolve(
            // relativize misbehaves on empty paths
            if (from.segments.isEmpty) path else from.toNIO.relativize(path)
          )
      }
      .getOrElse(path)
  }

  def normalizePath(path: os.Path): os.FilePath = mapPathPrefixes(path, mapping)

  def denormalizePath(path: java.nio.file.Path): java.nio.file.Path =
    mapPathPrefixes2(path, mapping.map(_.swap))

  def serializeString(p: os.Path): String = normalizePath(p) match {
    case p: os.SubPath => p.toNIO.toString
    case p: os.Path => p.wrapped.toString
  }

  def serializeFile(p: os.Path): java.io.File = normalizePath(p) match {
    case p: os.SubPath => p.toNIO.toFile
    case p: os.Path => p.wrapped.toFile
  }

  def serializePath(p: os.Path): java.nio.file.Path = normalizePath(p) match {
    case p: os.SubPath => p.toNIO
    case p: os.Path => p.wrapped
  }

  def deserialize(s: String) = denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

  def deserialize(s: java.io.File) = denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

  def deserialize(s: java.nio.file.Path) =
    denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

  def deserialize(s: java.net.URI) = denormalizePath(os.Path.defaultPathSerializer.deserialize(s))

}
