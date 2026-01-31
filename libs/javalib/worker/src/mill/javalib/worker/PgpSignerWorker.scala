package mill.javalib.worker

import mill.javalib.api.{PgpKeyMaterial, PgpWorkerApi}
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.operator.bc._
import org.bouncycastle.openpgp.operator.jcajce.{
  JcaPGPContentSignerBuilder,
  JcaPGPDigestCalculatorProviderBuilder,
  JcaPGPKeyPair
}
import org.bouncycastle.openpgp.{
  PGPException,
  PGPPublicKeyRing,
  PGPSecretKeyRing,
  PGPSecretKeyRingCollection
}
import org.bouncycastle.openpgp.{
  PGPSignature,
  PGPSignatureGenerator,
  PGPSignatureSubpacketGenerator,
  PGPUtil
}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.security.{KeyPairGenerator, SecureRandom, Security}
import java.util.{Base64, Collections}

final class PgpSignerWorker extends PgpWorkerApi {
  Security.addProvider(new BouncyCastleProvider())

  override def signDetached(
      file: os.Path,
      secretKeyBase64: String,
      keyIdHint: Option[String],
      passphrase: Option[String]
  ): os.Path = {
    val secretKey = loadSecretKey(secretKeyBase64, keyIdHint)
    val privateKey = extractPrivateKey(secretKey, passphrase)
    val signatureGenerator = new PGPSignatureGenerator(
      new BcPGPContentSignerBuilder(
        secretKey.getPublicKey.getAlgorithm,
        HashAlgorithmTags.SHA512
      )
    )
    signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, privateKey)

    val sigOut = new ByteArrayOutputStream()
    val armoredOut = new ArmoredOutputStream(sigOut)
    val bytes = os.read.bytes(file)
    signatureGenerator.update(bytes)
    signatureGenerator.generate().encode(armoredOut)
    armoredOut.close()

    val outPath = os.temp(dir = file / os.up, prefix = file.last + "-", suffix = ".asc")
    os.write.over(outPath, sigOut.toByteArray)
    outPath
  }

  override def extractSigningKeyId(secretKeyBase64: String): String = {
    val secretKey = loadSecretKey(secretKeyBase64, None)
    java.lang.Long.toHexString(secretKey.getKeyID).toUpperCase
  }

  override def generateKeyPair(
      userId: String,
      passphrase: Option[String]
  ): PgpKeyMaterial = {
    val keyPairGen = KeyPairGenerator.getInstance("RSA")
    keyPairGen.initialize(2048, SecureRandom.getInstanceStrong())
    val keyPair = keyPairGen.generateKeyPair()
    val pgpKeyPair = new JcaPGPKeyPair(
      PublicKeyAlgorithmTags.RSA_SIGN,
      keyPair,
      new java.util.Date()
    )
    val digestCalc =
      new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)
    val keyFlags = new PGPSignatureSubpacketGenerator()
    keyFlags.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER)

    val contentSignerBuilder = new JcaPGPContentSignerBuilder(
      pgpKeyPair.getPublicKey.getAlgorithm,
      HashAlgorithmTags.SHA256
    )
    val secretKey = new org.bouncycastle.openpgp.PGPSecretKey(
      PGPSignature.DEFAULT_CERTIFICATION,
      pgpKeyPair,
      userId,
      digestCalc,
      keyFlags.generate(),
      null,
      contentSignerBuilder,
      new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, digestCalc)
        .build(passphrase.map(_.toCharArray).orNull)
    )

    val publicKeyRing = new PGPPublicKeyRing(Collections.singletonList(secretKey.getPublicKey))
    val secretKeyRing = new PGPSecretKeyRing(Collections.singletonList(secretKey))

    val publicArmored = armor(publicKeyRing.getEncoded)
    val secretArmored = armor(secretKeyRing.getEncoded)

    PgpKeyMaterial(
      keyIdHex = java.lang.Long.toHexString(secretKey.getKeyID).toUpperCase,
      publicKeyArmored = publicArmored,
      secretKeyArmored = secretArmored
    )
  }

  private def loadSecretKey(
      secretKeyBase64: String,
      keyIdHint: Option[String]
  ): org.bouncycastle.openpgp.PGPSecretKey = {
    val decoded = Base64.getDecoder.decode(secretKeyBase64)
    val in = PGPUtil.getDecoderStream(new ByteArrayInputStream(decoded))
    val secretKeyRingCollection =
      new PGPSecretKeyRingCollection(in, new BcKeyFingerprintCalculator())
    val keys = secretKeyRingCollection.getKeyRings
    while (keys.hasNext) {
      val ring = keys.next().asInstanceOf[PGPSecretKeyRing]
      val secretKeys = ring.getSecretKeys
      while (secretKeys.hasNext) {
        val key = secretKeys.next()
        val keyIdHex = java.lang.Long.toHexString(key.getKeyID).toUpperCase
        if (key.isSigningKey && keyIdHint.forall(_.equalsIgnoreCase(keyIdHex))) return key
      }
    }
    throw new PGPException("No signing key found in secret key ring.")
  }

  private def extractPrivateKey(
      secretKey: org.bouncycastle.openpgp.PGPSecretKey,
      passphrase: Option[String]
  ) = {
    val decryptor = new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider())
      .build(passphrase.map(_.toCharArray).orNull)
    secretKey.extractPrivateKey(decryptor)
  }

  private def armor(bytes: Array[Byte]): String = {
    val out = new ByteArrayOutputStream()
    val armored = new ArmoredOutputStream(out)
    armored.write(bytes)
    armored.close()
    new String(out.toByteArray, StandardCharsets.UTF_8)
  }
}
