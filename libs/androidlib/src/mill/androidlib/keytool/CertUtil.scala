package mill.androidlib.keytool

import java.math.BigInteger
import java.security._
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.{JcaX509v3CertificateBuilder, JcaX509CertificateConverter}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider

object CertUtil:
  Security.addProvider(new BouncyCastleProvider())
  def createSelfSignedCertificate(
      dname: String,
      keyPair: KeyPair,
      validityDays: Int = 365
  ): X509Certificate = {
    val now = new Date()
    val notAfter = new Date(now.getTime + validityDays.toLong * 24 * 60 * 60 * 1000)

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
      .setProvider("BC")
      .build(keyPair.getPrivate)

    new JcaX509CertificateConverter()
      .setProvider("BC")
      .getCertificate(builder.build(signer))
  }
