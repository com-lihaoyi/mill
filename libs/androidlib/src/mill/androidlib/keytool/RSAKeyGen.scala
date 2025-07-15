package mill.androidlib.keytool

import java.security.{KeyPair, KeyPairGenerator}
import org.bouncycastle.jce.provider.BouncyCastleProvider

object RSAKeyGen {

  def generateKeyPair(keySize: Int = 2048): KeyPair = {
    val generator = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider())
    generator.initialize(keySize)
    generator.generateKeyPair()
  }

}
