package mill.androidlib.keytool

import java.security.{KeyPair, KeyPairGenerator, Security}
import org.bouncycastle.jce.provider.BouncyCastleProvider

object RSAKeyGen {

  Security.addProvider(new BouncyCastleProvider())

  def generateKeyPair(keySize: Int = 2048): KeyPair = {
    val generator = KeyPairGenerator.getInstance("RSA", "BC")
    generator.initialize(keySize)
    generator.generateKeyPair()
  }

}