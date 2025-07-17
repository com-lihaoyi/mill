package mill.androidlib.keytool
import mainargs.{ParserForMethods, arg, main}

@mill.api.experimental
object Keytool {
  @main
  def main(
            @arg(name = "keystore") keystorePath: String,
            @arg(name = "alias") alias: String,
            @arg(name = "keypass") keyPassword: String,
            @arg(name = "storepass") storePassword: String,
            @arg(name = "dname") dname: String,
            @arg(name = "validity-days") validityDays: Int = 365
          ): Unit = {
    println(s"Keystore Path: $keystorePath")
    println(s"Alias: $alias")
    println(s"Key Password: $keyPassword")
    println(s"Store Password: $storePassword")
    println(s"DName: $dname")
    println(s"Validity Days: $validityDays")
    val keystore = Keystore.createKeystore()
    val keyPair = RSAKeyGen.generateKeyPair()
    Keystore.addKeyPair(ks = keystore,
                        alias = alias,
                        keyPair = keyPair,
                        password = keyPassword,
                        dname = dname,
                        validityDays = validityDays)
    Keystore.saveKeystore(ks = keystore,
                          filePath = keystorePath,
                          password = storePassword)
//    os.call((
//      "keytool",
//      "-genkeypair",
//      "-keystore", keystorePath,
//      "-alias", alias,
//      "-keypass", keyPassword,
//      "-storepass", storePassword,
//      "-dname", dname,
//      "-validity", validityDays.toString,
//      "-keyalg", "RSA",
//      "-keysize", "2048",
//    ))
  }
  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
