package mill.contrib.sbom

import mill.*
import mill.contrib.sbom.CycloneDXModule.Component
import mill.javalib.{BoundDep, JavaModule}
import coursier.{Artifacts, Dependency, Resolution, VersionConstraint, core as cs}
import os.Path
import upickle.default.{ReadWriter, macroRW}

import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.{UUID}

object CycloneDXModule {
  case class SBOM_JSON(
      bomFormat: String,
      specVersion: String,
      serialNumber: String,
      version: Int,
      metadata: MetaData,
      components: Seq[Component]
  )

  case class MetaData(timestamp: String = Instant.now().toString)

  case class ComponentHash(alg: String, content: String)

  case class LicenseHolder(license: License)

  case class License(name: String, url: Option[String])

  case class Component(
      `type`: String,
      `bom-ref`: String,
      group: String,
      name: String,
      version: String,
      description: String,
      licenses: Seq[LicenseHolder],
      hashes: Seq[ComponentHash]
  )

  object Component {
    def fromDeps(path: Path, dep: Dependency, licenses: Seq[coursier.Info.License]): Component = {
      val compLicenses = licenses.map { lic =>
        LicenseHolder(License(lic.name, lic.url))
      }
      Component(
        "library",
        s"pkg:maven/${dep.module.organization.value}/${dep.module.name.value}@${dep.version}?type=jar",
        dep.module.organization.value,
        dep.module.name.value,
        dep.version,
        dep.module.orgName,
        compLicenses,
        Seq(ComponentHash("SHA-256", sha256(path)))
      )
    }
  }

  implicit val sbomRW: ReadWriter[SBOM_JSON] = macroRW
  implicit val metaRW: ReadWriter[MetaData] = macroRW
  implicit val componentHashRW: ReadWriter[ComponentHash] = macroRW
  implicit val componentRW: ReadWriter[Component] = macroRW
  implicit val licenceHolderRW: ReadWriter[LicenseHolder] = macroRW
  implicit val licenceRW: ReadWriter[License] = macroRW

  private def sha256(f: Path): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val fileContent = os.read.bytes(f)
    val digest = md.digest(fileContent)
    String.format("%0" + (digest.length << 1) + "x", new BigInteger(1, digest))
  }

  case class SbomHeader(serialNumber: UUID, timestamp: Instant)

}

trait CycloneDXJavaModule extends JavaModule with CycloneDXModule {

  /**
   * Lists of all components used for this module.
   * By default, uses the [[ivyDeps]] and [[runIvyDeps]] for the list of components
   */
  def sbomComponents: Task[Seq[Component]] = Task {
    val (resolution, artifacts) = resolvedRunIvyDepsDetails()()
    resolvedSbomComponents(resolution, artifacts)
  }

  protected def resolvedSbomComponents(
      resolution: Resolution,
      artifacts: Artifacts.Result
  ): Seq[Component] = {
    val distinctDeps = artifacts.fullDetailedArtifacts
      .flatMap {
        case (dep, _, _, Some(path)) => Some(dep -> path)
        case _ => None
      }
      // Artifacts.Result.files does eliminate duplicates path: Do the same
      .distinctBy(_._2)
      .map { case (dep, path) =>
        val license = findLicenses(resolution, dep.module, dep.versionConstraint)
        Component.fromDeps(os.Path(path), dep, license)
      }
    distinctDeps
  }

  /** Copied from [[resolvedRunIvyDeps]], but getting the raw artifacts */
  private def resolvedRunIvyDepsDetails(): Task[(Resolution, Artifacts.Result)] = Task.Anon {
    millResolver().artifacts(Seq(
      BoundDep(
        coursierDependency.withConfiguration(cs.Configuration.runtime),
        force = false
      )
    ))
  }

  private def findLicenses(
      resolution: Resolution,
      module: coursier.core.Module,
      version: VersionConstraint
  ): Seq[coursier.Info.License] = {
    val projects = resolution.projectCache0
    val project = projects.get(module -> version)
    project match
      case None => Seq.empty
      case Some((_, proj)) =>
        val licences = proj.info.licenseInfo
        if (licences.nonEmpty) {
          licences
        } else {
          proj.parent0.map((pm, v) =>
            findLicenses(resolution, pm, VersionConstraint.fromVersion(v))
          )
            .getOrElse(Seq.empty)
        }
  }

}

trait CycloneDXModule extends Module {
  import CycloneDXModule.*

  /** Lists of all components used for this module. */
  def sbomComponents: Task[Agg[Component]]

  /**
   * Each time the SBOM is generated, a new UUID and timestamp are generated
   * Can be overridden to use a more predictable method, eg. for reproducible builds
   */
  def sbomHeader(): SbomHeader = SbomHeader(UUID.randomUUID(), Instant.now())

  /**
   * Generates the SBOM Json for this module, based on the components returned by [[sbomComponents]]
   * @return
   */
  def sbom: T[SBOM_JSON] = Target {
    val header = sbomHeader()
    val components = sbomComponents()

    SBOM_JSON(
      bomFormat = "CycloneDX",
      specVersion = "1.2",
      serialNumber = s"urn:uuid:${header.serialNumber}",
      version = 1,
      metadata = MetaData(timestamp = header.timestamp.toString),
      components = components
    )
  }

  def sbomJsonFile: T[PathRef] = Target {
    val sbomFile = Target.dest / "sbom.json"
    os.write(sbomFile, upickle.default.write(sbom(), indent = 2))
    PathRef(sbomFile)
  }

}
