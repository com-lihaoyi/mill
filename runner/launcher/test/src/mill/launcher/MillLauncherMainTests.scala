package mill.launcher

import utest.*

import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{TrustManagerFactory, X509TrustManager}
import scala.util.Using

object MillLauncherMainTests extends TestSuite {
  val tests = Tests {
    test("withSystemProperties") {
      test("restore") {
        val existingKey = "mill.test.issue6888.existing"
        val addedKey = "mill.test.issue6888.added"
        val originalExisting = Option(System.getProperty(existingKey))
        val originalAdded = Option(System.getProperty(addedKey))

        try {
          System.setProperty(existingKey, "original")
          System.clearProperty(addedKey)

          MillLauncherMain.withSystemProperties(
            Map(existingKey -> "overridden", addedKey -> "added")
          ) {
            assert(System.getProperty(existingKey) == "overridden")
            assert(System.getProperty(addedKey) == "added")
          }

          assert(System.getProperty(existingKey) == "original")
          assert(System.getProperty(addedKey) == null)
        } finally {
          originalExisting match {
            case Some(value) => System.setProperty(existingKey, value)
            case None => System.clearProperty(existingKey)
          }
          originalAdded match {
            case Some(value) => System.setProperty(addedKey, value)
            case None => System.clearProperty(addedKey)
          }
        }
      }

      test("serializeConcurrentInvocations") {
        val key = "mill.test.issue6888.concurrent"
        val original = Option(System.getProperty(key))
        val firstEntered = new CountDownLatch(1)
        val releaseFirst = new CountDownLatch(1)
        val secondStarted = new CountDownLatch(1)
        val secondEntered = new CountDownLatch(1)
        val threadFailure = new AtomicReference[Throwable]()

        def captureFailure(run: => Unit): Unit =
          try run
          catch { case throwable: Throwable => threadFailure.compareAndSet(null, throwable) }

        val first = new Thread(() =>
          captureFailure {
            MillLauncherMain.withSystemProperties(Map(key -> "first")) {
              assert(System.getProperty(key) == "first")
              firstEntered.countDown()
              assert(releaseFirst.await(5, TimeUnit.SECONDS))
              assert(System.getProperty(key) == "first")
            }
          }
        )
        val second = new Thread(() =>
          captureFailure {
            assert(firstEntered.await(5, TimeUnit.SECONDS))
            secondStarted.countDown()
            MillLauncherMain.withSystemProperties(Map(key -> "second")) {
              assert(System.getProperty(key) == "second")
              secondEntered.countDown()
            }
          }
        )

        try {
          first.start()
          second.start()
          assert(secondStarted.await(5, TimeUnit.SECONDS))
          assert(!secondEntered.await(250, TimeUnit.MILLISECONDS))
          releaseFirst.countDown()
          assert(secondEntered.await(5, TimeUnit.SECONDS))
          first.join(5000)
          second.join(5000)
          assert(!first.isAlive, !second.isAlive)
          Option(threadFailure.get()).foreach(throw _)
        } finally {
          releaseFirst.countDown()
          first.join(5000)
          second.join(5000)
          original match {
            case Some(value) => System.setProperty(key, value)
            case None => System.clearProperty(key)
          }
        }
      }

      test("initializeJsseTrustStore") {
        val password = "issue-6888".toCharArray
        val trustStore = Files.createTempFile("mill-issue-6888-", ".p12")
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, password)
        Using.resource(Files.newOutputStream(trustStore))(keyStore.store(_, password))

        try {
          val acceptedIssuers = MillLauncherMain.withSystemProperties(Map(
            "javax.net.ssl.trustStore" -> trustStore.toString,
            "javax.net.ssl.trustStorePassword" -> String(password),
            "javax.net.ssl.trustStoreType" -> "PKCS12"
          )) {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
            factory.init(null.asInstanceOf[KeyStore])
            factory.getTrustManagers.collectFirst {
              case manager: X509TrustManager => manager.getAcceptedIssuers.toSeq
            }.get
          }

          assert(acceptedIssuers.isEmpty)
        } finally Files.deleteIfExists(trustStore)
      }
    }
  }
}
