package mill.util

import mill.util.Version
import utest.{TestSuite, Tests, assert, assertAll, test}

object VersionTests extends TestSuite {

  val tests = Tests {
    test("ordering") {
      val versions = Seq(
        "1.0",
        "1.2.3.alpha1",
        "1",
        "0.10.0",
        "1.0.0",
        "1_1",
        "0.10.0-M2",
        "1.0.0-alpha+001",
        "1.0.0+20130313144700",
        "1.0.0-beta+exp.sha.5114f85",
        "1.0.0+21AF26D3----117B344092BD"
      )
      test("ignoreQualifier") {
        val ordering = Version.IgnoreQualifierOrdering
        val input =
          versions.map(Version.parse).sorted(using ordering).map(_.toString())
        val expected = Seq(
          "0.10.0",
          "0.10.0-M2",
          "1.0",
          "1",
          "1.0.0",
          "1_1",
          "1.0.0-alpha+001",
          "1.0.0+20130313144700",
          "1.0.0-beta+exp.sha.5114f85",
          "1.0.0+21AF26D3----117B344092BD",
          "1.2.3.alpha1"
        )
        assert(input == expected)
      }
      test("maven") {
        val ordering = Version.MavenOrdering
        val input = versions.map(Version.parse).sorted(using ordering).map(_.toString())
        val expected = Seq(
          "0.10.0-M2",
          "0.10.0",
          "1_1",
          "1.0.0+20130313144700",
          "1.0.0+21AF26D3----117B344092BD",
          "1.0.0-alpha+001",
          "1.0.0-beta+exp.sha.5114f85",
          "1.0",
          "1",
          "1.0.0",
          "1.2.3.alpha1"
        )
        assert(input == expected)
      }
      test("osgi") {
        val ordering = Version.OsgiOrdering
        val input = versions.map(Version.parse).sorted(using ordering).map(_.toString())
        val expected = Seq(
          "0.10.0",
          "0.10.0-M2",
          "1.0",
          "1",
          "1.0.0",
          "1_1",
          "1.0.0+20130313144700",
          "1.0.0+21AF26D3----117B344092BD",
          "1.0.0-alpha+001",
          "1.0.0-beta+exp.sha.5114f85",
          "1.2.3.alpha1"
        )
        assert(input == expected)
      }
    }
    test("Version.isAtLeast(String, String)") {
      test("ignoreQualifier") {
        given Ordering[Version] = Version.IgnoreQualifierOrdering
        assertAll(
          Version.isAtLeast("0.8.9", "0.7.10") == true,
          Version.isAtLeast("0.8.9", "0.8.10") == false,
          Version.isAtLeast("0.8.10", "0.8.10") == true,
          Version.isAtLeast("0.8.10", "0.9.10") == false
        )
      }
    }
  }
}
