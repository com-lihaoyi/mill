package mil.scalalib

import mill.scalalib.{CrossVersion, Dep}

/**
 * Same as [[Dep]] but with already bound cross and platform settings.
 */
case class BoundDep(
    dep: coursier.Dependency,
    force: Boolean
) {

  def toDep: Dep = Dep(dep = dep, cross = CrossVersion.empty(false), force = force)

  def exclude(exclusions: (String, String)*) = copy(
    dep = dep.withExclusions(
      dep.exclusions ++
        exclusions.map { case (k, v) => (coursier.Organization(k), coursier.ModuleName(v)) }
    )
  )
}
