package mill.scalalib

import java.math.BigInteger
import java.security.MessageDigest

import ammonite.ops.Path
import ammonite.ops.RelPath
import mill.util.Logger
import mill._

object VendorBinary {

  case class VendorBinaryInfo(url: String, sha265: String, path: RelPath)

  private val digest = MessageDigest.getInstance("SHA-256")

  private def checkDigest(path: Path, binDigest: String, log: Logger) = {
    val content = digest.digest(java.nio.file.Files.readAllBytes(path.toIO.toPath))
    val hex = String.format("%040x", new BigInteger(1, content))
    digest.reset()
    val valid = hex.equals(binDigest)
    if (!valid) {
      log.error(s"SHA-256 digest mismatch! Expected '$binDigest' but got '$hex'")
    }
    valid
  }

  private def fetchAndCheck(url: String, dest: Path, relPath: RelPath, binDigest: String, log: Logger) = {
    log.info(s"Trying to fetch ${relPath.toString()} from '$url'")
    val bin = mill.modules.Util.download(
      url,
      relPath)(dest)
    if (!checkDigest(bin.path, binDigest, log)) {
      log.error(s"Fetching jetty-runner failed!")
      throw new Exception(s"Fetching jetty-runner failed!")
    } else {
      log.info(s"Digest match for ${relPath.toString()}: '$binDigest'")
      bin
    }
  }

  def vendorBinary(binary: VendorBinaryInfo, dest: Path, log: Logger): PathRef = {
    val binPath = (dest / binary.path)
    if (binPath.toIO.exists()) {
      val valid = checkDigest(binPath, binary.sha265, log)
      if (!valid) {
        fetchAndCheck(binary.url, dest, binary.path, binary.sha265, log)
      } else {
        PathRef(binPath)
      }
    } else {
      fetchAndCheck(binary.url, dest, binary.path, binary.sha265, log)
    }
  }

}
