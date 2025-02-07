package mill.main.client

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64

object Util extends UtilCommon {

  /**
   * @return
   *   Hex encoded MD5 hash of input string.
   */
  def md5hex(str: String): String = {
    // Sha1.hash(str.getBytes).mkString
    hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)))
  }

  def sha1Hash(path: String): String = {
    val md = MessageDigest.getInstance("SHA1")
    md.reset()
    val pathBytes = path.getBytes(StandardCharsets.UTF_8)
    md.update(pathBytes)
    val digest = md.digest()
    Base64.getEncoder.encodeToString(digest)
  }

}
