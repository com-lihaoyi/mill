package mill.androidlib.keytool
import mainargs.{ParserForMethods, arg, main, TokensReader, Flag}
import scala.concurrent.duration.*

@mill.api.experimental
object Keytool {

  implicit object FiniteDurationReader extends TokensReader.Simple[FiniteDuration] {
    def shortName: String = "validity"
    def read(tokens: Seq[String]): Either[String, FiniteDuration] = {
      if (tokens.isEmpty)
        throw new IllegalArgumentException("Duration cannot be empty")
      val durationString = tokens.mkString(" ")
      val duration = Duration(durationString)
      if (duration < Duration.Zero)
        throw new IllegalArgumentException(s"Duration cannot be negative: $durationString")
      duration match {
        case d: FiniteDuration => Right(d)
        case _ => throw new IllegalArgumentException(s"Duration must be finite: $durationString")
      }
    }
  }

  @main
  def main(
      @arg(name = "keystore") keystorePath: String,
      @arg(name = "alias") alias: String,
      @arg(name = "keypass") keyPassword: String,
      @arg(name = "storepass") storePassword: String,
      @arg(name = "dname") dname: String,
      @arg(name = "validity") validity: FiniteDuration = Duration(10000, DAYS),
      @arg(name = "skip-if-exists") skipIfExists: Flag
  ): Unit = {
    if (skipIfExists.value && os.exists(os.Path(keystorePath)))
      return
    val keystore = Keystore.createKeystore()
    val keyPair = RSAKeyGen.generateKeyPair()
    Keystore.addKeyPair(
      ks = keystore,
      alias = alias,
      keyPair = keyPair,
      password = keyPassword,
      dname = dname,
      validity = validity
    )
    Keystore.saveKeystore(ks = keystore, filePath = keystorePath, password = storePassword)
  }
  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
