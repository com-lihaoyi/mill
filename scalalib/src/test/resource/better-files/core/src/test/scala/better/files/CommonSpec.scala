package better.files

import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Properties.{isLinux, isMac}

trait CommonSpec extends FlatSpec with BeforeAndAfterEach with Matchers {
  val isCI = sys.env.get("CI").exists(_.toBoolean)

  val isUnixOS = isLinux || isMac

  def sleep(t: FiniteDuration = 2 second) = Thread.sleep(t.toMillis)
}
