package mill.javalib.pgp.worker

import org.bouncycastle.openpgp.{
  PGPException,
  PGPObjectFactory,
  PGPSecretKeyRing,
  PGPSecretKeyRingCollection,
  PGPSignatureList,
  PGPUtil
}
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import utest.*

import java.io.ByteArrayInputStream
import java.util.{Base64, Collections}

object PgpSignerWorkerTests extends TestSuite {
  private val primaryKeyId = "E8A21E824E4A74E7"
  private val signingSubkeyId = "F4C734E7BFEABACA"

  // Generated with `gpg --armor --export-secret-subkeys <signing-subkey-id>`.
  // The signing-capable primary key is an empty GNU stub; the subkey retains private material.
  private val secretKeyBase64 = {
    val stream = getClass.getResourceAsStream("/signing-subkey.asc")
    try Base64.getEncoder.encodeToString(stream.readAllBytes())
    finally stream.close()
  }

  private val primaryOnlySecretKeyBase64 = {
    val decoded = Base64.getDecoder.decode(secretKeyBase64)
    val input = PGPUtil.getDecoderStream(ByteArrayInputStream(decoded))
    try {
      val collection = PGPSecretKeyRingCollection(input, BcKeyFingerprintCalculator())
      val primaryKey = collection.getKeyRings.next().getSecretKey
      val ring = PGPSecretKeyRing(Collections.singletonList(primaryKey))
      Base64.getEncoder.encodeToString(ring.getEncoded)
    } finally input.close()
  }

  private def signatureKeyId(path: os.Path): String = {
    val input = PGPUtil.getDecoderStream(ByteArrayInputStream(os.read.bytes(path)))
    try {
      val objects = PGPObjectFactory(input, BcKeyFingerprintCalculator())
      val signature = objects.nextObject().asInstanceOf[PGPSignatureList].get(0)
      java.lang.Long.toHexString(signature.getKeyID).toUpperCase
    } finally input.close()
  }

  val tests: Tests = Tests {
    test("exportedSigningSubkey") {
      val worker = PgpSignerWorker()
      val input = os.temp(contents = "signed contents")

      test("selectedByDefault") {
        val signature = worker.signDetached(input, secretKeyBase64, None, None)
        assert(signatureKeyId(signature) == signingSubkeyId)
        assert(worker.extractSigningKeyId(secretKeyBase64) == signingSubkeyId)
      }

      test("selectedExplicitly") {
        val signature =
          worker.signDetached(input, secretKeyBase64, Some(signingSubkeyId), None)
        assert(signatureKeyId(signature) == signingSubkeyId)
      }

      test("emptyPrimarySelectedExplicitly") {
        val exception = assertThrows[PGPException] {
          worker.signDetached(input, secretKeyBase64, Some(primaryKeyId), None)
        }
        assert(exception.getMessage == s"Signing key $primaryKeyId has no private key material.")
      }

      test("noPrivateKeyMaterial") {
        val exception = assertThrows[PGPException] {
          worker.signDetached(input, primaryOnlySecretKeyBase64, None, None)
        }
        assert(exception.getMessage == s"Signing key $primaryKeyId has no private key material.")
      }

      test("requestedKeyNotFound") {
        val missingKeyId = "0123456789ABCDEF"
        val exception = assertThrows[PGPException] {
          worker.signDetached(input, secretKeyBase64, Some(missingKeyId), None)
        }
        assert(
          exception.getMessage ==
            s"No signing key matching key ID $missingKeyId found in secret key ring."
        )
      }
    }
  }
}
