/**
 * Utility code that is shared between our SBT build and our Mill build. SBT
 * calls this by shelling out to Ammonite in a subprocess, while Mill loads it
 * via import $file
 */

import $ivy.`org.scalaj::scalaj-http:2.4.2`
import mainargs.main

def unpackZip(zipDest: os.Path, url: String) = {
  println(s"Unpacking zip $url into $zipDest")
  os.makeDir.all(zipDest)

  val bytes =
    scalaj.http.Http.apply(url).option(scalaj.http.HttpOptions.followRedirects(true)).asBytes
  val byteStream = new java.io.ByteArrayInputStream(bytes.body)
  val zipStream = new java.util.zip.ZipInputStream(byteStream)
  while ({
    zipStream.getNextEntry match {
      case null => false
      case entry =>
        if (!entry.isDirectory) {
          val dest = zipDest / os.RelPath(entry.getName)
          os.makeDir.all(dest / os.up)
          val fileOut = new java.io.FileOutputStream(dest.toString)
          val buffer = new Array[Byte](4096)
          while ({
            zipStream.read(buffer) match {
              case -1 => false
              case n =>
                fileOut.write(buffer, 0, n)
                true
            }
          }) ()
          fileOut.close()
        }
        zipStream.closeEntry()
        true
    }
  }) ()
}

@main
def downloadTestRepo(label: String, commit: String, dest: os.Path) = {
  unpackZip(dest, s"https://github.com/$label/archive/$commit.zip")
  dest
}

/**
 * Copy of os-lib copy utility providing an additional `mergeFolders` option.
 * See pr https://github.com/com-lihaoyi/os-lib/pull/65
 */
object mycopy {
  import os._
  import java.nio.file
  import java.nio.file.{CopyOption, LinkOption, StandardCopyOption, Files}
  def apply(
      from: os.Path,
      to: os.Path,
      followLinks: Boolean = true,
      replaceExisting: Boolean = false,
      copyAttributes: Boolean = false,
      createFolders: Boolean = false,
      mergeFolders: Boolean = false
  ): Unit = {
    if (createFolders) makeDir.all(to / up)
    val opts1 =
      if (followLinks) Array[CopyOption]()
      else Array[CopyOption](LinkOption.NOFOLLOW_LINKS)
    val opts2 =
      if (replaceExisting) Array[CopyOption](StandardCopyOption.REPLACE_EXISTING)
      else Array[CopyOption]()
    val opts3 =
      if (copyAttributes) Array[CopyOption](StandardCopyOption.COPY_ATTRIBUTES)
      else Array[CopyOption]()
    require(
      !to.startsWith(from),
      s"Can't copy a directory into itself: $to is inside $from"
    )

    def copyOne(p: os.Path): file.Path = {
      val target = to / p.relativeTo(from)
      if (mergeFolders && isDir(p, followLinks) && isDir(target, followLinks)) {
        // nothing to do
        target.wrapped
      } else {
        Files.copy(p.wrapped, target.wrapped, opts1 ++ opts2 ++ opts3: _*)
      }
    }

    copyOne(from)
    if (stat(from, followLinks).isDir) walk(from).map(copyOne)
  }

  object into {
    def apply(
        from: os.Path,
        to: os.Path,
        followLinks: Boolean = true,
        replaceExisting: Boolean = false,
        copyAttributes: Boolean = false,
        createFolders: Boolean = false,
        mergeFolders: Boolean = false
    ): Unit = {
      mycopy(
        from,
        to / from.last,
        followLinks,
        replaceExisting,
        copyAttributes,
        createFolders,
        mergeFolders
      )
    }
  }
}
