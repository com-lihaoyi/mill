package mill.scalalib

import os.{Path, RelPath}

/**
 * A bunch of files with their contents.
 *
 * @tparam Contents Allows to specify in the type system that we only have a certain type of contents.
 */
case class FileSetContents[+Contents](
    contents: Map[RelPath, Contents]
) {

  def mapPaths(f: RelPath => RelPath): FileSetContents[Contents] =
    FileSetContents(contents.map { case (to, content) => f(to) -> content })

  def map[Contents2](f: (RelPath, Contents) => (RelPath, Contents2)): FileSetContents[Contents2] =
    FileSetContents[Contents2](contents.iterator.map { case (path, contents) => f(path, contents) }.toMap)

  def flatMap[Contents2](f: (RelPath, Contents) => Map[RelPath, Contents2]): FileSetContents[Contents2] =
    FileSetContents[Contents2](contents.iterator.flatMap { case (path, contents) => f(path, contents) }.toMap)

  /** Merges two sets together. */
  def ++[Contents2 >: Contents](other: FileSetContents[Contents2]): FileSetContents[Contents2] =
    FileSetContents[Contents2](contents ++ other.contents)

  def keysSorted: Vector[RelPath] =
    contents.keys.toVector.sorted

  /**
   * Writes the contents to the given directory.
   *
   * @return The paths to the written files.
   */
  def writeTo(dir: Path)(using Contents <:< FileSetContents.Contents): Vector[Path] = {
    contents.iterator.map { case (to, content) =>
      val destination = to.resolveFrom(dir)

      content match {
        case FileSetContents.Contents.Path(from) =>
          os.copy.over(from, destination, createFolders = true)
        case FileSetContents.Contents.String(content) =>
          os.write.over(destination, content, createFolders = true)
        case FileSetContents.Contents.Bytes(bytes) =>
          os.write.over(destination, bytes.asInstanceOf, createFolders = true)
      }

      destination
    }.toVector
  }
}
object FileSetContents {

  /** Allows any type of contents. */
  type Any = FileSetContents[Contents]

  /** Only allows file paths as contents. */
  type Path = FileSetContents[Contents.Path]

  /** Only allows string contents. */
  type String = FileSetContents[Contents.String]

  /** Only allows bytes contents. */
  type Bytes = FileSetContents[Contents.Bytes]

  /** Creates an empty set of contents. */
  def empty[Contents]: FileSetContents[Contents] =
    apply(Map.empty)

  /** @note Not an enum because we actually want the most specific type when referring to cases for the generics in
   *       [[FileSetContents]] to work properly. */
  sealed trait Contents
  object Contents {

    /** String contents that will be written to a file as UTF-8. */
    case class String(content: java.lang.String) extends Contents

    /** Path contents that will be copied to a file. */
    case class Path(path: os.Path) extends Contents

    /** Bytes contents that will be written to a file. */
    case class Bytes(bytes: IArray[Byte]) extends Contents
  }
}
