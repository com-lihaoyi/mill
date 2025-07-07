package mill.util

import os.{Path, SubPath}

/**
 * A bunch of relative file paths with their contents.
 *
 * @tparam Contents Allows specifying in the type system that we only have a certain type of contents.
 */
case class FileSetContents[+Contents](
    contents: Map[SubPath, Contents]
) {

  def mapPaths(f: SubPath => SubPath): FileSetContents[Contents] =
    FileSetContents(contents.map { case (to, content) => f(to) -> content })

  def mapContents[Contents2](f: Contents => Contents2): FileSetContents[Contents2] =
    FileSetContents(contents.map { case (to, content) => to -> f(content) })

  def map[Contents2](f: (SubPath, Contents) => (SubPath, Contents2)): FileSetContents[Contents2] =
    FileSetContents[Contents2](contents.iterator.map { case (path, contents) =>
      f(path, contents)
    }.toMap)

  def flatMap[Contents2](f: (
      SubPath,
      Contents
  ) => Map[SubPath, Contents2]): FileSetContents[Contents2] =
    FileSetContents[Contents2](contents.iterator.flatMap { case (path, contents) =>
      f(path, contents)
    }.toMap)

  /** Merges two sets together. */
  def ++[Contents2 >: Contents](other: Map[SubPath, Contents2]): FileSetContents[Contents2] =
    FileSetContents[Contents2](contents ++ other)

  /** Merges two sets together. */
  def ++[Contents2 >: Contents](other: FileSetContents[Contents2]): FileSetContents[Contents2] =
    this ++ other.contents

  def keysSorted: Vector[SubPath] =
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
  given upickleRw[Contents: upickle.default.ReadWriter]
      : upickle.default.ReadWriter[FileSetContents[Contents]] = {
    given upickle.default.ReadWriter[SubPath] =
      upickle.default.readwriter[java.lang.String].bimap(_.toString, SubPath(_))
    upickle.default.macroRW
  }

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

  /**
   * @note Not an enum because we actually want the most specific type when referring to cases for the generics in
   *       [[FileSetContents]] to work properly.
   */
  sealed trait Contents
  object Contents {

    /** String contents that will be written to a file as UTF-8. */
    case class String(content: java.lang.String) extends Contents

    /** Path contents that will be copied to a file. */
    case class Path(path: os.Path) extends Contents {
      def readFromDisk(): Bytes = FileSetContents.Contents.Bytes.fromArray(os.read.bytes(path))
    }

    /** Bytes contents that will be written to a file. */
    case class Bytes(bytes: IArray[Byte]) extends Contents {
      def bytesUnsafe: Array[Byte] = bytes.asInstanceOf
    }
    object Bytes {
      def fromArray(bytes: Array[Byte]): Bytes = apply(IArray.unsafeFromArray(bytes))
    }
  }

  def mergeAll[Contents](contents: IterableOnce[FileSetContents[Contents]])
      : FileSetContents[Contents] =
    contents.iterator.foldLeft(empty[Contents])(_ ++ _)
}
