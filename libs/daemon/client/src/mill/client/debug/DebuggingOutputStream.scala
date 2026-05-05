package mill.client.debug

import java.io.{FileOutputStream, OutputStream}
import java.nio.file.Path
import java.time.LocalDateTime

/** Writes everything you write to the output stream to a file as well. */
class DebuggingOutputStream(
    out: OutputStream,
    workingDir: Path,
    name: String,
    writeSeparateOps: Boolean
) extends OutputStream {

  private val debugOutput: OutputStream =
    new FileOutputStream(workingDir.resolve(name.replaceAll("\\W", "_")).toFile)

  override def write(b: Int): Unit = {
    if (writeSeparateOps)
      debugOutput.write(s"${LocalDateTime.now} write(): $b\n".getBytes)
    else
      debugOutput.write(b)

    out.write(b)

    if (writeSeparateOps)
      debugOutput.write(s"${LocalDateTime.now} done\n".getBytes)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    if (writeSeparateOps)
      debugOutput.write(
        s"${LocalDateTime.now} write(off=$off, len=$len): ${new String(b, off, len)}\n".getBytes
      )
    else
      debugOutput.write(b, off, len)

    out.write(b, off, len)

    if (writeSeparateOps)
      debugOutput.write(s"${LocalDateTime.now} done\n".getBytes)
  }
}
