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
          val dest = zipDest / os.SubPath(entry.getName)
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
