package mill.androidlib.keytool

import java.io.{File, FileInputStream, FileOutputStream}
import java.security.{Key, KeyPair, KeyStore}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

object Keystore:

  def createKeystore(ksType: String = "PKCS12"): KeyStore =
    val ks = KeyStore.getInstance(ksType)
    ks.load(null, null) // initialize empty keystore
    ks

  def saveKeystore(ks: KeyStore, filePath: String, password: String): Unit =
    saveKeystore(ks, new File(os.Path(filePath, os.pwd).toString), password.toCharArray)

  def saveKeystore(ks: KeyStore, filePath: os.Path, password: String): Unit =
    saveKeystore(ks, new File(filePath.toString), password.toCharArray)

  private def saveKeystore(ks: KeyStore, file: File, password: Array[Char]): Unit =
    // Ensure the parent directory exists
    if (!file.getParentFile.exists())
      throw new IllegalArgumentException(s"Parent directory does not exist: ${file.getParentFile}")
    val fos = new FileOutputStream(file)
    try ks.store(fos, password)
    finally fos.close()

  def saveEmptyKeystore(
      filePath: String,
      password: String,
      ksType: String = "PKCS12"
  ): Unit =
    val ks = createKeystore(ksType)
    saveKeystore(ks, filePath, password)

  def loadKeystore(filePath: String, password: String, ksType: String = "PKCS12"): KeyStore =
    loadKeystore(new File(filePath), password.toCharArray, ksType)

  private def loadKeystore(file: File, password: Array[Char], ksType: String): KeyStore =
    val ks = KeyStore.getInstance(ksType)
    val fis = new FileInputStream(file)
    try ks.load(fis, password)
    finally fis.close()
    ks

  def addKeyPair(
      ks: KeyStore,
      alias: String,
      keyPair: KeyPair,
      dname: String,
      password: String,
      validity: FiniteDuration = Duration(10000, DAYS)
  ): Unit =
    val cert = CertUtil.createSelfSignedCertificate(dname, keyPair, validity)
    ks.setKeyEntry(alias, keyPair.getPrivate, password.toCharArray, Array(cert))

  def listAliases(ks: KeyStore): List[String] =
    val aliases = ks.aliases()
    aliases.asScala.toList

  def getKey(ks: KeyStore, alias: String, password: String): Option[Key] =
    try {
      Some(ks.getKey(alias, password.toCharArray))
    } catch {
      case _: Exception =>
        None
    }
