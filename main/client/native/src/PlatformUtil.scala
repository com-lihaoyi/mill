package mill.main.client

import pt.kcry.sha._
import java.util.Base64
import java.nio.charset.{Charset, StandardCharsets}

object Util extends UtilCommon {

  /**
   * @return
   *   Hex encoded MD5 hash of input string.
   */
  def md5hex(str: String): String = {
    Sha1.hash(str.getBytes).mkString
  }

  def sha1Hash(path: String): String = {
    val sha1 = new Sha1()
    val hashed = Array.ofDim[Byte](20)
    sha1.update(path.getBytes(StandardCharsets.UTF_8), 0, path.length)
    sha1.finish(hashed, 0)
    Base64.getEncoder.encodeToString(hashed)
  }

}
