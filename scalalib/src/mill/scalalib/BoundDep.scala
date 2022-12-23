package mill.scalalib

import mill.scalalib.{CrossVersion, Dep}
import mill.scalalib.JsonFormatters._

/**
 * Same as [[Dep]] but with already bound cross and platform settings.
 */
case class BoundDep(
    dep: coursier.Dependency,
    force: Boolean
) {
  def organization = dep.module.organization.value
  def name = dep.module.name.value
  def version = dep.version

  def toDep: Dep = Dep(dep = dep, cross = CrossVersion.empty(false), force = force)

  def exclude(exclusions: (String, String)*) = copy(
    dep = dep.withExclusions(
      dep.exclusions ++
        exclusions.map { case (k, v) => (coursier.Organization(k), coursier.ModuleName(v)) }
    )
  )
}

object BoundDep {
  implicit val jsonify: upickle.default.ReadWriter[BoundDep] = upickle.default.macroRW
}
