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
package mill.scalalib.dependency.updates

import mill.scalalib.dependency.versions.{DependencyVersions, Version}
import utest._

object UpdatesFinderTests extends TestSuite {

  private def updates(current: String, available: Seq[String], allowPreRelease: Boolean) = {
    val dependency = coursier.Dependency(
      coursier.Module(
        coursier.Organization("com.example.organization"),
        coursier.ModuleName("example-artifact")
      ),
      current
    )
    val currentVersion = Version(current)
    val allVersions = available.map(Version(_)).toSet

    UpdatesFinder
      .findUpdates(DependencyVersions(dependency, currentVersion, allVersions), allowPreRelease)
      .updates
      .map(_.toString)
  }

  val available = Seq(
    "0.9.9-SNAPSHOT",
    "0.9.9-M3",
    "0.9.9",
    "1.0.0-SNAPSHOT",
    "1.0.0-M2",
    "1.0.0-M3",
    "1.0.0",
    "1.0.1-SNAPSHOT",
    "1.0.1-M3",
    "1.0.1"
  )

  val tests = Tests {

    "snapshotArtifacts" - {
      val u = updates("1.0.0-SNAPSHOT", available, allowPreRelease = false)
      val pu = updates("1.0.0-SNAPSHOT", available, allowPreRelease = true)

      "noOldStableVersions" - {
        assert(!u.contains("0.9.9"))
      }
      "noOldMilestones" - {
        assert(!u.contains("0.9.9-M3"))
      }
      "noOldSnapshots" - {
        assert(!u.contains("0.9.9-SNAPSHOT"))
      }
      "noCurrentMilestones" - {
        assert(!u.contains("1.0.0-M3"))
      }
      "noCurrentSnapshot" - {
        assert(!u.contains("1.0.0-SNAPSHOT"))
      }
      "stableUpdates" - {
        assert(u.contains("1.0.0") && u.contains("1.0.1"))
      }
      "milestoneUpdates" - {
        assert(u.contains("1.0.1-M3"))
      }
      "snapshotUpdates" - {
        assert(u.contains("1.0.1-SNAPSHOT"))
      }
      "noDifferencesRegardingOptionalPreReleases" - {
        assert(u == pu)
      }
    }

    "milestoneArtifacts" - {
      val u = updates("1.0.0-M2", available, allowPreRelease = false)
      val pu = updates("1.0.0-M2", available, allowPreRelease = true)

      "noOldStableVersions" - {
        assert(!u.contains("0.9.9"))
      }
      "noOldSnapshots" - {
        assert(!u.contains("0.9.9-SNAPSHOT"))
      }
      "noOldMilestones" - {
        assert(!u.contains("0.9.9-M3"))
      }
      "noCurrentSnapshot" - {
        assert(!u.contains("1.0.0-SNAPSHOT"))
      }
      "currentMilestones" - {
        assert(u.contains("1.0.0-M3"))
      }
      "stableUpdates" - {
        assert(u.contains("1.0.1"))
      }
      "noSnapshotUpdates" - {
        assert(!u.contains("1.0.1-SNAPSHOT"))
      }
      "milestoneUpdates" - {
        assert(u.contains("1.0.1-M3"))
      }
      "noDifferencesRegardingOptionalPreReleases" - {
        assert(u == pu)
      }
    }

    "stableArtifacts" - {
      val u = updates("1.0.0", available, allowPreRelease = false)
      val pu = updates("1.0.0", available, allowPreRelease = true)

      "noOldStableVersions" - {
        assert(!u.contains("0.9.9"))
        assert(!pu.contains("0.9.9"))
      }
      "noOldSnapshots" - {
        assert(!u.contains("0.9.9-SNAPSHOT"))
        assert(!pu.contains("0.9.9-SNAPSHOT"))
      }
      "noOldMilestones" - {
        assert(!u.contains("0.9.9-M3"))
        assert(!pu.contains("0.9.9-M3"))
      }
      "noCurrentSnapshot" - {
        assert(!u.contains("1.0.0-SNAPSHOT"))
        assert(!pu.contains("1.0.0-SNAPSHOT"))
      }
      "noCurrentMilestones" - {
        assert(!u.contains("1.0.0-M3"))
        assert(!pu.contains("1.0.0-M3"))
      }
      "stableUpdates" - {
        assert(u.contains("1.0.1"))
        assert(pu.contains("1.0.1"))
      }
      "noSnapshotUpdates" - {
        assert(!u.contains("1.0.1-SNAPSHOT"))
        assert(!pu.contains("1.0.1-SNAPSHOT"))
      }
      "noMilestoneUpdates" - {
        assert(!u.contains("1.0.1-M3"))
      }
      "milestoneUpdatesWhenAllowingPreReleases" - {
        assert(pu.contains("1.0.1-M3"))
      }
    }
  }
}
