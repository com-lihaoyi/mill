package mill.modules

import ammonite.ops.RelPath
import mill.eval.PathRef
import mill.util.Ctx

object Util {
  def download(url: String, dest: RelPath)(implicit ctx: Ctx.DestCtx) = {
    ammonite.ops.mkdir(ctx.dest)
    val out = ctx.dest / dest

    val website = new java.net.URI(url).toURL
    val rbc = java.nio.channels.Channels.newChannel(website.openStream)
    try {
      val fos = new java.io.FileOutputStream(out.toIO)
      try {
        fos.getChannel.transferFrom(rbc, 0, java.lang.Long.MAX_VALUE)
        PathRef(out)
      } finally {
        fos.close()
      }
    } finally {
      rbc.close()
    }
  }
}
