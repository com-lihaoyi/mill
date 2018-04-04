package mill.modules

import java.io.{ByteArrayInputStream}
import ammonite.ops.{Path, RelPath, InteractiveShelloutException, empty, mkdir, read}
import mill.eval.PathRef
import mill.clientserver.InputPumper
import mill.util.{Ctx, IO}

object Util {
  def download(url: String, dest: RelPath = "download")(implicit ctx: Ctx.Dest) = {
    val out = ctx.dest / dest

    val website = new java.net.URI(url).toURL
    val rbc = java.nio.channels.Channels.newChannel(website.openStream)
    try{
      val fos = new java.io.FileOutputStream(out.toIO)
      try{
        fos.getChannel.transferFrom(rbc, 0, java.lang.Long.MAX_VALUE)
        PathRef(out)
      } finally{
        fos.close()
      }
    } finally{
      rbc.close()
    }
  }

  def downloadUnpackZip(url: String, dest: RelPath = "unpacked")
                       (implicit ctx: Ctx.Dest) = {

    val tmpName = if (dest == empty / "tmp.zip") "tmp2.zip" else "tmp.zip"
    val downloaded = download(url, tmpName)
    unpackZip(downloaded.path, dest)
  }

  def unpackZip(src: Path, dest: RelPath = "unpacked")
               (implicit ctx: Ctx.Dest) = {

    val byteStream = read.getInputStream(src)
    val zipStream = new java.util.zip.ZipInputStream(byteStream)
    while({
      zipStream.getNextEntry match{
        case null => false
        case entry =>
          if (!entry.isDirectory) {
            val entryDest = ctx.dest / dest / RelPath(entry.getName)
            mkdir(entryDest / ammonite.ops.up)
            val fileOut = new java.io.FileOutputStream(entryDest.toString)
            IO.stream(zipStream, fileOut)
            fileOut.close()
          }
          zipStream.closeEntry()
          true
      }
    })()
    PathRef(ctx.dest / dest)
  }

  def interactiveSubprocess(commandArgs: Seq[String],
                            envArgs: Map[String, String],
                            workingDir: Path) = {
    val builder = new java.lang.ProcessBuilder()

    for ((k, v) <- envArgs){
      if (v != null) builder.environment().put(k, v)
      else builder.environment().remove(k)
    }
    builder.directory(workingDir.toIO)

    val process = if (System.in.isInstanceOf[ByteArrayInputStream]){

      val process = builder
        .command(commandArgs:_*)
        .start()

      val sources = Seq(
        process.getInputStream -> System.out,
        process.getErrorStream -> System.err,
        System.in -> process.getOutputStream
      )

      for((std, dest) <- sources){
        new Thread(new InputPumper(std, dest, false)).start()
      }
      process
    } else {
      builder
        .command(commandArgs:_*)
        .inheritIO()
        .start()
    }

    val exitCode = process.waitFor()
    if (exitCode == 0) ()
    else throw InteractiveShelloutException()
  }
}
