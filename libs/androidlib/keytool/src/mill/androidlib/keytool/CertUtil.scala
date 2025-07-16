package mill.androidlib.keytool

import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import scala.concurrent.duration.*

object CertUtil:
  def createSelfSignedCertificate(
      dname: String,
      keyPair: KeyPair,
      validity: FiniteDuration = FiniteDuration(365, DAYS)
  ): X509Certificate = {
    val now = new Date()
    val notAfter = new Date(now.getTime + validity.toMillis)

    val builder: X509v3CertificateBuilder =
      new JcaX509v3CertificateBuilder(
        new X500Principal(dname), // issuer
        BigInteger.valueOf(System.currentTimeMillis()), // serial number
        now, // start date
        notAfter, // end date
        new X500Principal(dname), // subject
        keyPair.getPublic // public key
      )

    val signer = new JcaContentSignerBuilder("SHA256withRSA")
      .setProvider(new BouncyCastleProvider())
      .build(keyPair.getPrivate)

    new JcaX509CertificateConverter()
      .setProvider(new BouncyCastleProvider())
      .getCertificate(builder.build(signer))
  }
