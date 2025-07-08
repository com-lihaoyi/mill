package mill.util

import os.{Path, SubPath}

object FileSetContents {
  type Writable = String | Path | Array[Byte]

  /**
   * Writes the contents to the given directory.
   *
   * @return The paths to the written files.
   */
  def writeTo(dir: Path, contents: Map[SubPath, Writable]): Vector[Path] = {
    contents.iterator.map { case (to, content) =>
      val destination = to.resolveFrom(dir)

      content match {
        case from: Path =>
          os.copy.over(from, destination, createFolders = true)
        case content: String =>
          os.write.over(destination, content, createFolders = true)
        case bytes: Array[Byte] @unchecked =>
          os.write.over(destination, bytes, createFolders = true)
      }

      destination
    }.toVector
  }
}
