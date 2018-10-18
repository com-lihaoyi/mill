/*
 * This file contains code originally published under the following license:
 *
 * Copyright (c) 2012, Roman Timushev
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * The name of the author may not be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package mill.scalalib.dependency.versions

import utest._
import fastparse.Parsed

object VersionTests extends TestSuite {

  val tests = Tests {
    'versionsClassification - {
      'ReleaseVersion - {
        List("1.0.0", "1.0.0.Final", "1.0.0-FINAL", "1.0.0.RELEASE") foreach {
          rel =>
            assertMatch(Version(rel)) {
              case ReleaseVersion(List(1, 0, 0)) =>
            }
        }
      }
      'PreReleaseVersion - {
        assertMatch(Version("1.0.0-alpha.1")) {
          case PreReleaseVersion(List(1, 0, 0), List("alpha", "1")) =>
        }
      }
      'PreReleaseBuildVersion - {
        assertMatch(Version("1.0.0-alpha.1+build.10")) {
          case PreReleaseBuildVersion(List(1, 0, 0),
                                      List("alpha", "1"),
                                      List("build", "10")) =>
        }
      }
      'BuildVersion - {
        assertMatch(Version("1.0.0+build.10")) {
          case BuildVersion(List(1, 0, 0), List("build", "10")) =>
        }
      }
    }

    'semverVersionsOrdering - {
      import scala.Ordered._

      val v = List(
        "invalid",
        "1.0.0-20131213005945",
        "1.0.0-alpha",
        "1.0.0-alpha.1",
        "1.0.0-beta.2",
        "1.0.0-beta.11",
        "1.0.0-rc.1",
        "1.0.0-rc.1+build.1",
        "1.0.0",
        "1.0.0+0.3.7",
        "1.33.7+build",
        "1.33.7+build.2.b8f12d7",
        "1.33.7+build.11.e0f985a",
        "2.0.M5b",
        "2.0.M6-SNAP9",
        "2.0.M6-SNAP23",
        "2.0.M6-SNAP23a"
      ).map(Version.apply)
      val pairs = v.tails.flatMap {
        case h :: t => t.map((h, _))
        case Nil    => List.empty
      }
      pairs.foreach {
        case (a, b) =>
          assert(a < b)
          assert(b > a)
      }
    }

    'parser - {

      Symbol("parse 1.0.5") - {
        assertMatch(VersionParser.parse("1.0.5")) {
          case Parsed.Success((Seq(1, 0, 5), Seq(), Seq()), _) =>
        }
      }

      Symbol("parse 1.0.M3") - {
        assertMatch(VersionParser.parse("1.0.M3")) {
          case Parsed.Success((Seq(1, 0), Seq("M3"), Seq()), _) =>
        }
      }
      Symbol("parse 1.0.3m") - {
        assertMatch(VersionParser.parse("1.0.3m")) {
          case Parsed.Success((Seq(1, 0), Seq("3m"), Seq()), _) =>
        }
      }
      Symbol("parse 1.0.3m.4") - {
        assertMatch(VersionParser.parse("1.0.3m.4")) {
          case Parsed.Success((Seq(1, 0), Seq("3m", "4"), Seq()), _) =>
        }
      }
      Symbol("parse  9.1-901-1.jdbc4") - {
        assertMatch(VersionParser.parse("9.1-901-1.jdbc4")) {
          case Parsed.Success((Seq(9, 1), Seq("901", "1", "jdbc4"), Seq()), _) =>
        }
      }
      Symbol("parse 1.33.7+build/11.e0f985a") - {
        assertMatch(VersionParser.parse("1.33.7+build/11.e0f985a")) {
          case Parsed.Success((Seq(1, 33, 7), Seq(), Seq("build/11", "e0f985a")), _) =>
        }
      }
      Symbol("parse 9.1-901-1.jdbc4+build/11.e0f985a") - {
        assertMatch(VersionParser.parse("9.1-901-1.jdbc4+build/11.e0f985a")) {
          case Parsed.Success((Seq(9, 1), Seq("901", "1", "jdbc4"), Seq("build/11", "e0f985a")), _) =>
        }
      }
    }
  }
}
