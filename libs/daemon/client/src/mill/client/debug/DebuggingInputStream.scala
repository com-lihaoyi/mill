package mill.client.debug

import java.io.{FileOutputStream, InputStream, OutputStream}
import java.nio.file.Path
import java.time.LocalDateTime

/** Writes everything you read from the input stream to a file as well. */
class DebuggingInputStream(
    in: InputStream,
    workingDir: Path,
    name: String,
    writeSeparateOps: Boolean
) extends InputStream {

  private val debugOutput: OutputStream =
    new FileOutputStream(workingDir.resolve(name.replaceAll("\\W", "_")).toFile)

  override def read(): Int = {
    val b = in.read()
    if (b != -1) {
      if (writeSeparateOps)
        debugOutput.write(s"${LocalDateTime.now} read(): $b\n".getBytes)
      else
        debugOutput.write(b)
    }
    b
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bytesRead = in.read(b, off, len)
    if (bytesRead != -1) {
      if (writeSeparateOps)
        debugOutput.write(
          s"${LocalDateTime.now} readArray(off=$off, len=$len, bytesRead=$bytesRead): ${new String(b, off, bytesRead)}\n".getBytes
        )
      else
        debugOutput.write(b, off, bytesRead)
    }
    bytesRead
  }

  override def close(): Unit = {
    try super.close()
    finally debugOutput.close()
  }
}
