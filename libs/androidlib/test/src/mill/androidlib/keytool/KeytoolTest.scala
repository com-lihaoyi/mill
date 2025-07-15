package mill.androidlib.keytool

import utest.*
import scala.util.Try

object KeytoolTest extends TestSuite {

  def tests: Tests = Tests {
    test("create and save empty keystore") {
      val filename = "keystore_empty.test"
      val password = "password"
      Keystore.saveEmptyKeystore(filename, password)
      val output = getKeytoolOutput("-list", "-keystore", filename, "-storepass", password)
      removeFile(filename)
      assert(output.contains("Your keystore contains 0 entries"))
    }
    test("create a keystore and add a key pair") {
      val filename = "keystore_add_key.test"
      val password = "password"
      val keyPair = RSAKeyGen.generateKeyPair(2048)
      val ks = Keystore.createKeystore()
      Keystore.addKeyPair(ks, "mykey", keyPair, "CN=TEST", password)
      Keystore.saveKeystore(ks, filename, password)
      val loadedKs = Keystore.loadKeystore(filename, password)
      val aliases = Keystore.listAliases(loadedKs)
      assert(aliases.contains("mykey"))
      val output = getKeytoolOutput("-list", "-keystore", filename, "-storepass", password)
      removeFile(filename)
      assert(output.contains("Your keystore contains 1 entry") && output.contains("mykey"))
    }
    test("load keystore and verify key pair") {
      val filename = "keystore_load_key.test"
      val password = "password"
      val keyPair = RSAKeyGen.generateKeyPair(2048)
      val ks = Keystore.createKeystore()
      Keystore.addKeyPair(ks, "mykey", keyPair, "CN=TEST", password)
      Keystore.saveKeystore(ks, filename, password)
      val loadedKs = Keystore.loadKeystore(filename, password)
      val key = Keystore.getKey(loadedKs, "mykey", password)
      assert(key.isDefined)
      assert(key.get == keyPair.getPrivate)
    }
    test("do NOT load keystore with wrong password") {
      val filename = "keystore_wrong_password.test"
      val password = "password"
      val wrongPassword = "wrongpassword"
      Keystore.saveEmptyKeystore(filename, password)
      assert(Try(Keystore.loadKeystore(filename, wrongPassword)).isFailure)
      removeFile(filename)
    }
    test("load keystore and do NOT load key pair with wrong password") {
      val filename = "keystore_load_wrong_key.test"
      val password = "password"
      val wrongPassword = "wrongpassword"
      val keyPair = RSAKeyGen.generateKeyPair(2048)
      val ks = Keystore.createKeystore()
      Keystore.addKeyPair(ks, "mykey", keyPair, "CN=TEST", password)
      Keystore.saveKeystore(ks, filename, password)
      val loadedKs = Keystore.loadKeystore(filename, password)
      val key = Keystore.getKey(loadedKs, "mykey", wrongPassword)
      assert(key.isEmpty)
    }
  }
}

def getKeytoolOutput(args: String*): String = {
  import scala.sys.process.*
  val cmd = Seq("keytool") ++ args
  cmd.!!
}

def removeFile(filename: String): Unit = {
  import java.io.File
  val file = new File(filename)
  if (file.exists()) {
    if (!file.delete()) {
      throw new RuntimeException(s"Failed to delete file: $filename")
    }
  }
}
