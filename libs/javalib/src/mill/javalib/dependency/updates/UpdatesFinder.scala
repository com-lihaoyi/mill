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
package mill.javalib.dependency.updates

import mill.javalib.dependency.versions._

import scala.collection.SortedSet

private[dependency] object UpdatesFinder {

  import scala.Ordered._

  def findUpdates(
      dependencyVersions: ModuleDependenciesVersions,
      allowPreRelease: Boolean
  ): ModuleDependenciesUpdates = {
    val dependencies =
      dependencyVersions.dependencies.map { dependencyVersion =>
        findUpdates(dependencyVersion, allowPreRelease)
      }
    ModuleDependenciesUpdates(dependencyVersions.modulePath, dependencies)
  }

  def findUpdates(
      dependencyVersion: DependencyVersions,
      allowPreRelease: Boolean
  ): DependencyUpdates = {
    val current = dependencyVersion.currentVersion
    val versions = dependencyVersion.allversions.to(SortedSet)

    val updates = versions
      .filter(isUpdate(current))
      .filterNot(lessStable(current, allowPreRelease))

    DependencyUpdates(dependencyVersion.dependency, dependencyVersion.currentVersion, updates)
  }

  private def lessStable(current: Version, allowPreRelease: Boolean)(
      another: Version
  ): Boolean = (current, another) match {
    case (ReleaseVersion(_), ReleaseVersion(_)) => false
    case (SnapshotVersion(_, _, _), _) => false
    case (_, SnapshotVersion(_, _, _)) => true
    case (ReleaseVersion(_), PreReleaseVersion(_, _)) => !allowPreRelease
    case (ReleaseVersion(_), PreReleaseBuildVersion(_, _, _)) =>
      !allowPreRelease
    case (ReleaseVersion(_), _) => true
    case (_, _) => false
  }

  private def isUpdate(current: Version) = current < (_: Version)
}
