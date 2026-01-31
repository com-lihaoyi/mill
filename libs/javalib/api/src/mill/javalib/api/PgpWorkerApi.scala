package mill.javalib.api

/** Interface for PGP signing and key management in a worker classloader. */
trait PgpWorkerApi {
  def signDetached(
      file: os.Path,
      secretKeyBase64: String,
      keyIdHint: Option[String],
      passphrase: Option[String]
  ): os.Path

  def extractSigningKeyId(secretKeyBase64: String): String

  def generateKeyPair(userId: String, passphrase: Option[String]): PgpKeyMaterial
}

final case class PgpKeyMaterial(
    keyIdHex: String,
    publicKeyArmored: String,
    secretKeyArmored: String
)
