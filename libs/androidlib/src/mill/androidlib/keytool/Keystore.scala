package mill.androidlib.keytool

// Manages keystore operations

import java.security.KeyStore
import java.io.{FileInputStream, FileOutputStream, File}

object Keystore:

  def createKeystore(ksType: String = "PKCS12"): KeyStore =
    val ks = KeyStore.getInstance(ksType)
    ks.load(null, null) // initialize empty keystore
    ks

  def saveKeystore(ks: KeyStore, filePath: String, password: String): Unit =
    saveKeystore(ks, new File(filePath), password.toCharArray)

  def saveKeystore(ks: KeyStore, file: File, password: Array[Char]): Unit =
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
      keyPair: java.security.KeyPair,
      dname: String,
      password: String
  ): Unit =
    val cert = CertUtil.createSelfSignedCertificate(dname, keyPair)
    ks.setKeyEntry(alias, keyPair.getPrivate, password.toCharArray, Array(cert))

  def listAliases(ks: KeyStore): List[String] =
    val aliases = ks.aliases()
    Iterator
      .continually(aliases)
      .takeWhile(_.hasMoreElements)
      .map(_.nextElement())
      .toList

  def getKey(ks: KeyStore, alias: String, password: String): Option[java.security.Key] =
    try {
      Some(ks.getKey(alias, password.toCharArray))
    } catch {
      case _: Exception =>
        None
    }
