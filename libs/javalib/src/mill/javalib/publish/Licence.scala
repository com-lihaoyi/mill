package mill.javalib.publish

/**
 * Models an open source software license. Most common licenses are provided
 * in the companion object, e.g. [[License.MIT]], [[License.`Apache-2.0`]], etc.
 */
case class License(
    id: String,
    name: String,
    url: String,
    isOsiApproved: Boolean,
    isFsfLibre: Boolean,
    distribution: String
)

object License {
  // ujson.read(requests.get("https://raw.githubusercontent.com/spdx/license-list-data/master/json/licenses.json"))
  // show(res0("licenses").arr.map(_("licenseId").str))
  private[mill] lazy val knownMap = known.map(l => l.id -> l).toMap
  private lazy val known = Seq(
    `0BSD`,
      `AAL`,
      `Abstyles`,
      `Adobe-2006`,
      `Adobe-Glyph`,
      `ADSL`,
      `AFL-1.1`,
      `AFL-1.2`,
      `AFL-2.0`,
      `AFL-2.1`,
      `AFL-3.0`,
      `Afmparse`,
      `AGPL-1.0`,
      `AGPL-3.0-only`,
      `AGPL-3.0-or-later`,
      `Aladdin`,
      `AMDPLPA`,
      `AML`,
      `AMPAS`,
      `ANTLR-PD`,
      `Apache-1.0`,
      `Apache-1.1`,
      `Apache-2.0`,
      `APAFML`,
      `APL-1.0`,
      `APSL-1.0`,
      `APSL-1.1`,
      `APSL-1.2`,
      `APSL-2.0`,
      `Artistic-1.0-cl8`,
      `Artistic-1.0-Perl`,
      `Artistic-1.0`,
      `Artistic-2.0`,
      `Bahyph`,
      `Barr`,
      `Beerware`,
      `BitTorrent-1.0`,
      `BitTorrent-1.1`,
      `Borceux`,
      `BSD-1-Clause`,
      `BSD-2-Clause-FreeBSD`,
      `BSD-2-Clause-NetBSD`,
      `BSD-2-Clause-Patent`,
      `BSD-2-Clause`,
      `BSD-3-Clause-Attribution`,
      `BSD-3-Clause-Clear`,
      `BSD-3-Clause-LBNL`,
      `BSD-3-Clause-No-Nuclear-License-2014`,
      `BSD-3-Clause-No-Nuclear-License`,
      `BSD-3-Clause-No-Nuclear-Warranty`,
      `BSD-3-Clause`,
      `BSD-4-Clause-UC`,
      `BSD-4-Clause`,
      `BSD-Protection`,
      `BSD-Source-Code`,
      `BSL-1.0`,
      `bzip2-1.0.5`,
  `bzip2-1.0.6`,
  `Caldera`,
  `CATOSL-1.1`,
  `CC-BY-1.0`,
  `CC-BY-2.0`,
  `CC-BY-2.5`,
  `CC-BY-3.0`,
  `CC-BY-4.0`,
  `CC-BY-NC-1.0`,
  `CC-BY-NC-2.0`,
  `CC-BY-NC-2.5`,
  `CC-BY-NC-3.0`,
  `CC-BY-NC-4.0`,
  `CC-BY-NC-ND-1.0`,
  `CC-BY-NC-ND-2.0`,
  `CC-BY-NC-ND-2.5`,
  `CC-BY-NC-ND-3.0`,
  `CC-BY-NC-ND-4.0`,
  `CC-BY-NC-SA-1.0`,
  `CC-BY-NC-SA-2.0`,
  `CC-BY-NC-SA-2.5`,
  `CC-BY-NC-SA-3.0`,
  `CC-BY-NC-SA-4.0`,
  `CC-BY-ND-1.0`,
  `CC-BY-ND-2.0`,
  `CC-BY-ND-2.5`,
  `CC-BY-ND-3.0`,
  `CC-BY-ND-4.0`,
  `CC-BY-SA-1.0`,
  `CC-BY-SA-2.0`,
  `CC-BY-SA-2.5`,
  `CC-BY-SA-3.0`,
  `CC-BY-SA-4.0`,
  `CC0-1.0`,
  `CDDL-1.0`,
  `CDDL-1.1`,
  `CDLA-Permissive-1.0`,
  `CDLA-Sharing-1.0`,
  `CECILL-1.0`,
  `CECILL-1.1`,
  `CECILL-2.0`,
  `CECILL-2.1`,
  `CECILL-B`,
  `CECILL-C`,
  `ClArtistic`,
  `CNRI-Jython`,
  `CNRI-Python-GPL-Compatible`,
  `CNRI-Python`,
  `Condor-1.1`,
  `CPAL-1.0`,
  `CPL-1.0`,
  `CPOL-1.02`,
  `Crossword`,
  `CrystalStacker`,
  `CUA-OPL-1.0`,
  `Cube`,
  `curl`,
  `D-FSL-1.0`,
  `diffmark`,
  `DOC`,
  `Dotseqn`,
  `DSDP`,
  `dvipdfm`,
  `ECL-1.0`,
  `ECL-2.0`,
  `EFL-1.0`,
  `EFL-2.0`,
  `eGenix`,
  `Entessa`,
  `EPL-1.0`,
  `EPL-2.0`,
  `ErlPL-1.1`,
  `EUDatagrid`,
  `EUPL-1.0`,
  `EUPL-1.1`,
  `EUPL-1.2`,
  `Eurosym`,
  `Fair`,
  `Frameworx-1.0`,
  `FreeImage`,
  `FSFAP`,
  `FSFUL`,
  `FSFULLR`,
  `FTL`,
  `GFDL-1.1-only`,
  `GFDL-1.1-or-later`,
  `GFDL-1.2-only`,
  `GFDL-1.2-or-later`,
  `GFDL-1.3-only`,
  `GFDL-1.3-or-later`,
  `Giftware`,
  `GL2PS`,
  `Glide`,
  `Glulxe`,
  `gnuplot`,
  `GPL-1.0-only`,
  `GPL-1.0-or-later`,
  `GPL-2.0-only`,
  `GPL-2.0-or-later`,
  `GPL-3.0-only`,
  `GPL-3.0-or-later`,
  `gSOAP-1.3b`,
    `HaskellReport`,
  `HPND`,
  `IBM-pibs`,
  `ICU`,
  `IJG`,
  `ImageMagick`,
  `iMatix`,
  `Imlib2`,
  `Info-ZIP`,
  `Intel-ACPI`,
  `Intel`,
  `Interbase-1.0`,
  `IPA`,
  `IPL-1.0`,
  `ISC`,
  `JasPer-2.0`,
  `JSON`,
  `LAL-1.2`,
  `LAL-1.3`,
  `Latex2e`,
  `Leptonica`,
  `LGPL-2.0-only`,
  `LGPL-2.0-or-later`,
  `LGPL-2.1-only`,
  `LGPL-2.1-or-later`,
  `LGPL-3.0-only`,
  `LGPL-3.0-or-later`,
  `LGPLLR`,
  `Libpng`,
  `libtiff`,
  `LiLiQ-P-1.1`,
  `LiLiQ-R-1.1`,
  `LiLiQ-Rplus-1.1`,
  `LPL-1.0`,
  `LPL-1.02`,
  `LPPL-1.0`,
  `LPPL-1.1`,
  `LPPL-1.2`,
  `LPPL-1.3a`,
    `LPPL-1.3c`,
    `MakeIndex`,
  `MirOS`,
  `MIT-advertising`,
  `MIT-CMU`,
  `MIT-enna`,
  `MIT-feh`,
  `MIT`,
  `MITNFA`,
  `Motosoto`,
  `mpich2`,
  `MPL-1.0`,
  `MPL-1.1`,
  `MPL-2.0-no-copyleft-exception`,
  `MPL-2.0`,
  `MS-PL`,
  `MS-RL`,
  `MTLL`,
  `Multics`,
  `Mup`,
  `NASA-1.3`,
  `Naumen`,
  `NBPL-1.0`,
  `NCSA`,
  `Net-SNMP`,
  `NetCDF`,
  `Newsletr`,
  `NGPL`,
  `NLOD-1.0`,
  `NLPL`,
  `Nokia`,
  `NOSL`,
  `Noweb`,
  `NPL-1.0`,
  `NPL-1.1`,
  `NPOSL-3.0`,
  `NRL`,
  `NTP`,
  `OCCT-PL`,
  `OCLC-2.0`,
  `ODbL-1.0`,
  `OFL-1.0`,
  `OFL-1.1`,
  `OGTSL`,
  `OLDAP-1.1`,
  `OLDAP-1.2`,
  `OLDAP-1.3`,
  `OLDAP-1.4`,
  `OLDAP-2.0.1`,
  `OLDAP-2.0`,
  `OLDAP-2.1`,
  `OLDAP-2.2.1`,
  `OLDAP-2.2.2`,
  `OLDAP-2.2`,
  `OLDAP-2.3`,
  `OLDAP-2.4`,
  `OLDAP-2.5`,
  `OLDAP-2.6`,
  `OLDAP-2.7`,
  `OLDAP-2.8`,
  `OML`,
  `OpenSSL`,
  `OPL-1.0`,
  `OSET-PL-2.1`,
  `OSL-1.0`,
  `OSL-1.1`,
  `OSL-2.0`,
  `OSL-2.1`,
  `OSL-3.0`,
  `PDDL-1.0`,
  `PHP-3.0`,
  `PHP-3.01`,
  `Plexus`,
  `PostgreSQL`,
  `psfrag`,
  `psutils`,
  `Python-2.0`,
  `Qhull`,
  `QPL-1.0`,
  `Rdisc`,
  `RHeCos-1.1`,
  `RPL-1.1`,
  `RPL-1.5`,
  `RPSL-1.0`,
  `RSA-MD`,
  `RSCPL`,
  `Ruby`,
  `SAX-PD`,
  `Saxpath`,
  `SCEA`,
  `Sendmail`,
  `SGI-B-1.0`,
  `SGI-B-1.1`,
  `SGI-B-2.0`,
  `SimPL-2.0`,
  `SISSL-1.2`,
  `SISSL`,
  `Sleepycat`,
  `SMLNJ`,
  `SMPPL`,
  `SNIA`,
  `Spencer-86`,
  `Spencer-94`,
  `Spencer-99`,
  `SPL-1.0`,
  `SugarCRM-1.1.3`,
  `SWL`,
  `TCL`,
  `TCP-wrappers`,
  `TMate`,
  `TORQUE-1.1`,
  `TOSL`,
  `Unicode-DFS-2015`,
  `Unicode-DFS-2016`,
  `Unicode-TOU`,
  `Unlicense`,
  `UPL-1.0`,
  `Vim`,
  `VOSTROM`,
  `VSL-1.0`,
  `W3C-19980720`,
  `W3C-20150513`,
  `W3C`,
  `Watcom-1.0`,
  `Wsuipa`,
  `WTFPL`,
  `X11`,
  `Xerox`,
  `XFree86-1.1`,
  `xinetd`,
  `Xnet`,
  `xpp`,
  `XSkat`,
  `YPL-1.0`,
  `YPL-1.1`,
  `Zed`,
  `Zend-2.0`,
  `Zimbra-1.3`,
  `Zimbra-1.4`,
  `zlib-acknowledgement`,
  `Zlib`,
  `ZPL-1.1`,
  `ZPL-2.0`,
  `ZPL-2.1`,
  `AGPL-3.0`,
  `eCos-2.0`,
  `GFDL-1.1`,
  `GFDL-1.2`,
  `GFDL-1.3`,
  `GPL-1.0+`,
    `GPL-1.0`,
  `GPL-2.0+`,
    `GPL-2.0-with-autoconf-exception`,
  `GPL-2.0-with-bison-exception`,
  `GPL-2.0-with-classpath-exception`,
  `GPL-2.0-with-font-exception`,
  `GPL-2.0-with-GCC-exception`,
  `GPL-2.0`,
  `GPL-3.0+`,
    `GPL-3.0-with-autoconf-exception`,
  `GPL-3.0-with-GCC-exception`,
  `GPL-3.0`,
  `LGPL-2.0+`,
    `LGPL-2.0`,
  `LGPL-2.1+`,
    `LGPL-2.1`,
  `LGPL-3.0+`,
    `LGPL-3.0`,
  `Nunit`,
  `StandardML-NJ`,
  `wxWindows`,
    // Misc
    PublicDomain,
    Scala,
    TypesafeSubscriptionAgreement,
  )
  /*
  wget https://raw.githubusercontent.com/spdx/license-list-data/master/json/licenses.json

  ```
  val circeVersion = "0.9.1"
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % circeVersion)

  import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
  import java.nio.file._
  import System.{lineSeparator => nl}
  case class License(
    reference: String,
    isDeprecatedLicenseId: Boolean,
    isFsfLibre: Option[Boolean],
    detailsUrl: String,
    referenceNumber: String,
    name: String,
    licenseId: String,
    seeAlso: Option[List[String]],
    isOsiApproved: Boolean
  ) {
    def ident: String = {
      val startsWithDigit = (0 to 9).map(_.toString).exists(licenseId.startsWith)
      if (licenseId.contains("-") || !startsWithDigit) s"`$licenseId`"
      else licenseId
    }

    def syntax(identPadding: Int, namePadding: Int): String = {
      val s1 = " " * (identPadding - ident.size)
      val s2 = " " * (namePadding - name.size)
      val ticks = if (ident == licenseId) 2 else 0
      val s3 = " " * (identPadding - ticks - ident.size)
      val s4 = if(isOsiApproved) " " else ""
      s"""val ${ident}${s1} = spdx(\"\"\"$name\"\"\",$s2 "$licenseId", $s3 $isOsiApproved, $s4 ${isFsfLibre.getOrElse(false)})"""
    }
  }


  case class Data(licenses: List[License])

  val json = new String(Files.readAllBytes(Paths.get("data.json")))

  val licences = decode[Data](json).right.get.licenses

  val identPadding = licences.map(_.licenseId.size + 2).max
  val namePadding = licences.map(_.name.size).max

  val output = licences.map(license => license.syntax(identPadding, namePadding)).mkString(nl)
  Files.write(Paths.get("out.scala"), output.getBytes("utf-8"))
   */
  val `0BSD`: License = spdx("BSD Zero Clause License", "0BSD", false, false)
  val AAL: License = spdx("Attribution Assurance License", "AAL", true, false)
  val Abstyles: License = spdx("Abstyles License", "Abstyles", false, false)
  val `Adobe-2006` =
    spdx("Adobe Systems Incorporated Source Code License Agreement", "Adobe-2006", false, false)
  val `Adobe-Glyph` = spdx("Adobe Glyph List License", "Adobe-Glyph", false, false)
  val ADSL: License = spdx("Amazon Digital Services License", "ADSL", false, false)
  val `AFL-1.1` = spdx("Academic Free License v1.1", "AFL-1.1", true, true)
  val `AFL-1.2` = spdx("Academic Free License v1.2", "AFL-1.2", true, true)
  val `AFL-2.0` = spdx("Academic Free License v2.0", "AFL-2.0", true, true)
  val `AFL-2.1` = spdx("Academic Free License v2.1", "AFL-2.1", true, true)
  val `AFL-3.0` = spdx("Academic Free License v3.0", "AFL-3.0", true, true)
  val Afmparse: License = spdx("Afmparse License", "Afmparse", false, false)
  val `AGPL-1.0` = spdx("Affero General Public License v1.0", "AGPL-1.0", false, true)
  val `AGPL-3.0-only` =
    spdx("GNU Affero General Public License v3.0 only", "AGPL-3.0-only", true, false)
  val `AGPL-3.0-or-later` =
    spdx("GNU Affero General Public License v3.0 or later", "AGPL-3.0-or-later", true, false)
  val Aladdin: License = spdx("Aladdin Free Public License", "Aladdin", false, false)
  val AMDPLPA: License = spdx("AMD's plpa_map.c License", "AMDPLPA", false, false)
  val AML: License = spdx("Apple MIT License", "AML", false, false)
  val AMPAS: License =
    spdx("Academy of Motion Picture Arts and Sciences BSD", "AMPAS", false, false)
  val `ANTLR-PD` = spdx("ANTLR Software Rights Notice", "ANTLR-PD", false, false)
  val `Apache-1.0` = spdx("Apache License 1.0", "Apache-1.0", false, true)
  val `Apache-1.1` = spdx("Apache License 1.1", "Apache-1.1", true, true)
  val `Apache-2.0` = spdx("Apache License 2.0", "Apache-2.0", true, true)
  val APAFML: License = spdx("Adobe Postscript AFM License", "APAFML", false, false)
  val `APL-1.0` = spdx("Adaptive Public License 1.0", "APL-1.0", true, false)
  val `APSL-1.0` = spdx("Apple Public Source License 1.0", "APSL-1.0", true, false)
  val `APSL-1.1` = spdx("Apple Public Source License 1.1", "APSL-1.1", true, false)
  val `APSL-1.2` = spdx("Apple Public Source License 1.2", "APSL-1.2", true, false)
  val `APSL-2.0` = spdx("Apple Public Source License 2.0", "APSL-2.0", true, true)
  val `Artistic-1.0-cl8` = spdx("Artistic License 1.0 w/clause 8", "Artistic-1.0-cl8", true, false)
  val `Artistic-1.0-Perl` = spdx("Artistic License 1.0 (Perl)", "Artistic-1.0-Perl", true, false)
  val `Artistic-1.0` = spdx("Artistic License 1.0", "Artistic-1.0", true, false)
  val `Artistic-2.0` = spdx("Artistic License 2.0", "Artistic-2.0", true, true)
  val Bahyph: License = spdx("Bahyph License", "Bahyph", false, false)
  val Barr: License = spdx("Barr License", "Barr", false, false)
  val Beerware: License = spdx("Beerware License", "Beerware", false, false)
  val `BitTorrent-1.0` = spdx("BitTorrent Open Source License v1.0", "BitTorrent-1.0", false, false)
  val `BitTorrent-1.1` = spdx("BitTorrent Open Source License v1.1", "BitTorrent-1.1", false, true)
  val Borceux: License = spdx("Borceux license", "Borceux", false, false)
  val `BSD-1-Clause` = spdx("BSD 1-Clause License", "BSD-1-Clause", false, false)
  val `BSD-2-Clause-FreeBSD` =
    spdx("BSD 2-Clause FreeBSD License", "BSD-2-Clause-FreeBSD", false, true)
  val `BSD-2-Clause-NetBSD` =
    spdx("BSD 2-Clause NetBSD License", "BSD-2-Clause-NetBSD", false, false)
  val `BSD-2-Clause-Patent` =
    spdx("BSD-2-Clause Plus Patent License", "BSD-2-Clause-Patent", true, false)
  val `BSD-2-Clause` = spdx("BSD 2-Clause \"Simplified\" License", "BSD-2-Clause", true, false)
  val `BSD-3-Clause-Attribution` =
    spdx("BSD with attribution", "BSD-3-Clause-Attribution", false, false)
  val `BSD-3-Clause-Clear` = spdx("BSD 3-Clause Clear License", "BSD-3-Clause-Clear", false, true)
  val `BSD-3-Clause-LBNL` =
    spdx("Lawrence Berkeley National Labs BSD variant license", "BSD-3-Clause-LBNL", false, false)
  val `BSD-3-Clause-No-Nuclear-License-2014` = spdx(
    "BSD 3-Clause No Nuclear License 2014",
    "BSD-3-Clause-No-Nuclear-License-2014",
    false,
    false
  )
  val `BSD-3-Clause-No-Nuclear-License` =
    spdx("BSD 3-Clause No Nuclear License", "BSD-3-Clause-No-Nuclear-License", false, false)
  val `BSD-3-Clause-No-Nuclear-Warranty` =
    spdx("BSD 3-Clause No Nuclear Warranty", "BSD-3-Clause-No-Nuclear-Warranty", false, false)
  val `BSD-3-Clause` =
    spdx("BSD 3-Clause \"New\" or \"Revised\" License", "BSD-3-Clause", true, true)
  val `BSD-4-Clause-UC` =
    spdx("BSD-4-Clause (University of California-Specific)", "BSD-4-Clause-UC", false, false)
  val `BSD-4-Clause` =
    spdx("BSD 4-Clause \"Original\" or \"Old\" License", "BSD-4-Clause", false, true)
  val `BSD-Protection` = spdx("BSD Protection License", "BSD-Protection", false, false)
  val `BSD-Source-Code` = spdx("BSD Source Code Attribution", "BSD-Source-Code", false, false)
  val `BSL-1.0` = spdx("Boost Software License 1.0", "BSL-1.0", true, true)
  val `bzip2-1.0.5` = spdx("bzip2 and libbzip2 License v1.0.5", "bzip2-1.0.5", false, false)
  val `bzip2-1.0.6` = spdx("bzip2 and libbzip2 License v1.0.6", "bzip2-1.0.6", false, false)
  val Caldera: License = spdx("Caldera License", "Caldera", false, false)
  val `CATOSL-1.1` =
    spdx("Computer Associates Trusted Open Source License 1.1", "CATOSL-1.1", true, false)
  val `CC-BY-1.0` = spdx("Creative Commons Attribution 1.0", "CC-BY-1.0", false, false)
  val `CC-BY-2.0` = spdx("Creative Commons Attribution 2.0", "CC-BY-2.0", false, false)
  val `CC-BY-2.5` = spdx("Creative Commons Attribution 2.5", "CC-BY-2.5", false, false)
  val `CC-BY-3.0` = spdx("Creative Commons Attribution 3.0", "CC-BY-3.0", false, false)
  val `CC-BY-4.0` = spdx("Creative Commons Attribution 4.0", "CC-BY-4.0", false, true)
  val `CC-BY-NC-1.0` =
    spdx("Creative Commons Attribution Non Commercial 1.0", "CC-BY-NC-1.0", false, false)
  val `CC-BY-NC-2.0` =
    spdx("Creative Commons Attribution Non Commercial 2.0", "CC-BY-NC-2.0", false, false)
  val `CC-BY-NC-2.5` =
    spdx("Creative Commons Attribution Non Commercial 2.5", "CC-BY-NC-2.5", false, false)
  val `CC-BY-NC-3.0` =
    spdx("Creative Commons Attribution Non Commercial 3.0", "CC-BY-NC-3.0", false, false)
  val `CC-BY-NC-4.0` =
    spdx("Creative Commons Attribution Non Commercial 4.0", "CC-BY-NC-4.0", false, false)
  val `CC-BY-NC-ND-1.0` = spdx(
    "Creative Commons Attribution Non Commercial No Derivatives 1.0",
    "CC-BY-NC-ND-1.0",
    false,
    false
  )
  val `CC-BY-NC-ND-2.0` = spdx(
    "Creative Commons Attribution Non Commercial No Derivatives 2.0",
    "CC-BY-NC-ND-2.0",
    false,
    false
  )
  val `CC-BY-NC-ND-2.5` = spdx(
    "Creative Commons Attribution Non Commercial No Derivatives 2.5",
    "CC-BY-NC-ND-2.5",
    false,
    false
  )
  val `CC-BY-NC-ND-3.0` = spdx(
    "Creative Commons Attribution Non Commercial No Derivatives 3.0",
    "CC-BY-NC-ND-3.0",
    false,
    false
  )
  val `CC-BY-NC-ND-4.0` = spdx(
    "Creative Commons Attribution Non Commercial No Derivatives 4.0",
    "CC-BY-NC-ND-4.0",
    false,
    false
  )
  val `CC-BY-NC-SA-1.0` = spdx(
    "Creative Commons Attribution Non Commercial Share Alike 1.0",
    "CC-BY-NC-SA-1.0",
    false,
    false
  )
  val `CC-BY-NC-SA-2.0` = spdx(
    "Creative Commons Attribution Non Commercial Share Alike 2.0",
    "CC-BY-NC-SA-2.0",
    false,
    false
  )
  val `CC-BY-NC-SA-2.5` = spdx(
    "Creative Commons Attribution Non Commercial Share Alike 2.5",
    "CC-BY-NC-SA-2.5",
    false,
    false
  )
  val `CC-BY-NC-SA-3.0` = spdx(
    "Creative Commons Attribution Non Commercial Share Alike 3.0",
    "CC-BY-NC-SA-3.0",
    false,
    false
  )
  val `CC-BY-NC-SA-4.0` = spdx(
    "Creative Commons Attribution Non Commercial Share Alike 4.0",
    "CC-BY-NC-SA-4.0",
    false,
    false
  )
  val `CC-BY-ND-1.0` =
    spdx("Creative Commons Attribution No Derivatives 1.0", "CC-BY-ND-1.0", false, false)
  val `CC-BY-ND-2.0` =
    spdx("Creative Commons Attribution No Derivatives 2.0", "CC-BY-ND-2.0", false, false)
  val `CC-BY-ND-2.5` =
    spdx("Creative Commons Attribution No Derivatives 2.5", "CC-BY-ND-2.5", false, false)
  val `CC-BY-ND-3.0` =
    spdx("Creative Commons Attribution No Derivatives 3.0", "CC-BY-ND-3.0", false, false)
  val `CC-BY-ND-4.0` =
    spdx("Creative Commons Attribution No Derivatives 4.0", "CC-BY-ND-4.0", false, false)
  val `CC-BY-SA-1.0` =
    spdx("Creative Commons Attribution Share Alike 1.0", "CC-BY-SA-1.0", false, false)
  val `CC-BY-SA-2.0` =
    spdx("Creative Commons Attribution Share Alike 2.0", "CC-BY-SA-2.0", false, false)
  val `CC-BY-SA-2.5` =
    spdx("Creative Commons Attribution Share Alike 2.5", "CC-BY-SA-2.5", false, false)
  val `CC-BY-SA-3.0` =
    spdx("Creative Commons Attribution Share Alike 3.0", "CC-BY-SA-3.0", false, false)
  val `CC-BY-SA-4.0` =
    spdx("Creative Commons Attribution Share Alike 4.0", "CC-BY-SA-4.0", false, true)
  val `CC0-1.0` = spdx("Creative Commons Zero v1.0 Universal", "CC0-1.0", false, true)
  val `CDDL-1.0` = spdx("Common Development and Distribution License 1.0", "CDDL-1.0", true, true)
  val `CDDL-1.1` = spdx("Common Development and Distribution License 1.1", "CDDL-1.1", false, false)
  val `CDLA-Permissive-1.0` =
    spdx("Community Data License Agreement Permissive 1.0", "CDLA-Permissive-1.0", false, false)
  val `CDLA-Sharing-1.0` =
    spdx("Community Data License Agreement Sharing 1.0", "CDLA-Sharing-1.0", false, false)
  val `CECILL-1.0` = spdx("CeCILL Free Software License Agreement v1.0", "CECILL-1.0", false, false)
  val `CECILL-1.1` = spdx("CeCILL Free Software License Agreement v1.1", "CECILL-1.1", false, false)
  val `CECILL-2.0` = spdx("CeCILL Free Software License Agreement v2.0", "CECILL-2.0", false, true)
  val `CECILL-2.1` = spdx("CeCILL Free Software License Agreement v2.1", "CECILL-2.1", true, false)
  val `CECILL-B` = spdx("CeCILL-B Free Software License Agreement", "CECILL-B", false, true)
  val `CECILL-C` = spdx("CeCILL-C Free Software License Agreement", "CECILL-C", false, true)
  val ClArtistic: License = spdx("Clarified Artistic License", "ClArtistic", false, true)
  val `CNRI-Jython` = spdx("CNRI Jython License", "CNRI-Jython", false, false)
  val `CNRI-Python-GPL-Compatible` = spdx(
    "CNRI Python Open Source GPL Compatible License Agreement",
    "CNRI-Python-GPL-Compatible",
    false,
    false
  )
  val `CNRI-Python` = spdx("CNRI Python License", "CNRI-Python", true, false)
  val `Condor-1.1` = spdx("Condor Public License v1.1", "Condor-1.1", false, true)
  val `CPAL-1.0` = spdx("Common Public Attribution License 1.0", "CPAL-1.0", true, true)
  val `CPL-1.0` = spdx("Common Public License 1.0", "CPL-1.0", true, true)
  val `CPOL-1.02` = spdx("Code Project Open License 1.02", "CPOL-1.02", false, false)
  val Crossword: License = spdx("Crossword License", "Crossword", false, false)
  val CrystalStacker: License = spdx("CrystalStacker License", "CrystalStacker", false, false)
  val `CUA-OPL-1.0` = spdx("CUA Office Public License v1.0", "CUA-OPL-1.0", true, false)
  val Cube: License = spdx("Cube License", "Cube", false, false)
  val curl: License = spdx("curl License", "curl", false, false)
  val `D-FSL-1.0` = spdx("Deutsche Freie Software Lizenz", "D-FSL-1.0", false, false)
  val diffmark: License = spdx("diffmark license", "diffmark", false, false)
  val DOC: License = spdx("DOC License", "DOC", false, false)
  val Dotseqn: License = spdx("Dotseqn License", "Dotseqn", false, false)
  val DSDP: License = spdx("DSDP License", "DSDP", false, false)
  val dvipdfm: License = spdx("dvipdfm License", "dvipdfm", false, false)
  val `ECL-1.0` = spdx("Educational Community License v1.0", "ECL-1.0", true, false)
  val `ECL-2.0` = spdx("Educational Community License v2.0", "ECL-2.0", true, true)
  val `EFL-1.0` = spdx("Eiffel Forum License v1.0", "EFL-1.0", true, false)
  val `EFL-2.0` = spdx("Eiffel Forum License v2.0", "EFL-2.0", true, true)
  val eGenix: License = spdx("eGenix.com Public License 1.1.0", "eGenix", false, false)
  val Entessa: License = spdx("Entessa Public License v1.0", "Entessa", true, false)
  val `EPL-1.0` = spdx("Eclipse Public License 1.0", "EPL-1.0", true, true)
  val `EPL-2.0` = spdx("Eclipse Public License 2.0", "EPL-2.0", true, true)
  val `ErlPL-1.1` = spdx("Erlang Public License v1.1", "ErlPL-1.1", false, false)
  val EUDatagrid: License = spdx("EU DataGrid Software License", "EUDatagrid", true, true)
  val `EUPL-1.0` = spdx("European Union Public License 1.0", "EUPL-1.0", false, false)
  val `EUPL-1.1` = spdx("European Union Public License 1.1", "EUPL-1.1", true, true)
  val `EUPL-1.2` = spdx("European Union Public License 1.2", "EUPL-1.2", true, false)
  val Eurosym: License = spdx("Eurosym License", "Eurosym", false, false)
  val Fair: License = spdx("Fair License", "Fair", true, false)
  val `Frameworx-1.0` = spdx("Frameworx Open License 1.0", "Frameworx-1.0", true, false)
  val FreeImage: License = spdx("FreeImage Public License v1.0", "FreeImage", false, false)
  val FSFAP: License = spdx("FSF All Permissive License", "FSFAP", false, true)
  val FSFUL: License = spdx("FSF Unlimited License", "FSFUL", false, false)
  val FSFULLR: License =
    spdx("FSF Unlimited License (with License Retention)", "FSFULLR", false, false)
  val FTL: License = spdx("Freetype Project License", "FTL", false, true)
  val `GFDL-1.1-only` =
    spdx("GNU Free Documentation License v1.1 only", "GFDL-1.1-only", false, false)
  val `GFDL-1.1-or-later` =
    spdx("GNU Free Documentation License v1.1 or later", "GFDL-1.1-or-later", false, false)
  val `GFDL-1.2-only` =
    spdx("GNU Free Documentation License v1.2 only", "GFDL-1.2-only", false, false)
  val `GFDL-1.2-or-later` =
    spdx("GNU Free Documentation License v1.2 or later", "GFDL-1.2-or-later", false, false)
  val `GFDL-1.3-only` =
    spdx("GNU Free Documentation License v1.3 only", "GFDL-1.3-only", false, false)
  val `GFDL-1.3-or-later` =
    spdx("GNU Free Documentation License v1.3 or later", "GFDL-1.3-or-later", false, false)
  val Giftware: License = spdx("Giftware License", "Giftware", false, false)
  val GL2PS: License = spdx("GL2PS License", "GL2PS", false, false)
  val Glide: License = spdx("3dfx Glide License", "Glide", false, false)
  val Glulxe: License = spdx("Glulxe License", "Glulxe", false, false)
  val gnuplot: License = spdx("gnuplot License", "gnuplot", false, true)
  val `GPL-1.0-only` = spdx("GNU General Public License v1.0 only", "GPL-1.0-only", false, false)
  val `GPL-1.0-or-later` =
    spdx("GNU General Public License v1.0 or later", "GPL-1.0-or-later", false, false)
  val `GPL-2.0-only` = spdx("GNU General Public License v2.0 only", "GPL-2.0-only", true, false)
  val `GPL-2.0-or-later` =
    spdx("GNU General Public License v2.0 or later", "GPL-2.0-or-later", true, false)
  val `GPL-3.0-only` = spdx("GNU General Public License v3.0 only", "GPL-3.0-only", true, false)
  val `GPL-3.0-or-later` =
    spdx("GNU General Public License v3.0 or later", "GPL-3.0-or-later", true, false)
  val `gSOAP-1.3b` = spdx("gSOAP Public License v1.3b", "gSOAP-1.3b", false, false)
  val HaskellReport: License =
    spdx("Haskell Language Report License", "HaskellReport", false, false)
  val HPND: License = spdx("Historical Permission Notice and Disclaimer", "HPND", true, true)
  val `IBM-pibs` = spdx("IBM PowerPC Initialization and Boot Software", "IBM-pibs", false, false)
  val ICU: License = spdx("ICU License", "ICU", false, false)
  val IJG: License = spdx("Independent JPEG Group License", "IJG", false, true)
  val ImageMagick: License = spdx("ImageMagick License", "ImageMagick", false, false)
  val iMatix: License = spdx("iMatix Standard Function Library Agreement", "iMatix", false, true)
  val Imlib2: License = spdx("Imlib2 License", "Imlib2", false, true)
  val `Info-ZIP` = spdx("Info-ZIP License", "Info-ZIP", false, false)
  val `Intel-ACPI` = spdx("Intel ACPI Software License Agreement", "Intel-ACPI", false, false)
  val Intel: License = spdx("Intel Open Source License", "Intel", true, true)
  val `Interbase-1.0` = spdx("Interbase Public License v1.0", "Interbase-1.0", false, false)
  val IPA: License = spdx("IPA Font License", "IPA", true, true)
  val `IPL-1.0` = spdx("IBM Public License v1.0", "IPL-1.0", true, true)
  val ISC: License = spdx("ISC License", "ISC", true, true)
  val `JasPer-2.0` = spdx("JasPer License", "JasPer-2.0", false, false)
  val JSON: License = spdx("JSON License", "JSON", false, false)
  val `LAL-1.2` = spdx("Licence Art Libre 1.2", "LAL-1.2", false, false)
  val `LAL-1.3` = spdx("Licence Art Libre 1.3", "LAL-1.3", false, false)
  val Latex2e: License = spdx("Latex2e License", "Latex2e", false, false)
  val Leptonica: License = spdx("Leptonica License", "Leptonica", false, false)
  val `LGPL-2.0-only` =
    spdx("GNU Library General Public License v2 only", "LGPL-2.0-only", true, false)
  val `LGPL-2.0-or-later` =
    spdx("GNU Library General Public License v2 or later", "LGPL-2.0-or-later", true, false)
  val `LGPL-2.1-only` =
    spdx("GNU Lesser General Public License v2.1 only", "LGPL-2.1-only", true, false)
  val `LGPL-2.1-or-later` =
    spdx("GNU Lesser General Public License v2.1 or later", "LGPL-2.1-or-later", true, false)
  val `LGPL-3.0-only` =
    spdx("GNU Lesser General Public License v3.0 only", "LGPL-3.0-only", true, false)
  val `LGPL-3.0-or-later` =
    spdx("GNU Lesser General Public License v3.0 or later", "LGPL-3.0-or-later", true, false)
  val LGPLLR: License =
    spdx("Lesser General Public License For Linguistic Resources", "LGPLLR", false, false)
  val Libpng: License = spdx("libpng License", "Libpng", false, false)
  val libtiff: License = spdx("libtiff License", "libtiff", false, false)
  val `LiLiQ-P-1.1` =
    spdx("Licence Libre du Québec – Permissive version 1.1", "LiLiQ-P-1.1", true, false)
  val `LiLiQ-R-1.1` =
    spdx("Licence Libre du Québec – Réciprocité version 1.1", "LiLiQ-R-1.1", true, false)
  val `LiLiQ-Rplus-1.1` =
    spdx("Licence Libre du Québec – Réciprocité forte version 1.1", "LiLiQ-Rplus-1.1", true, false)
  val `LPL-1.0` = spdx("Lucent Public License Version 1.0", "LPL-1.0", true, false)
  val `LPL-1.02` = spdx("Lucent Public License v1.02", "LPL-1.02", true, true)
  val `LPPL-1.0` = spdx("LaTeX Project Public License v1.0", "LPPL-1.0", false, false)
  val `LPPL-1.1` = spdx("LaTeX Project Public License v1.1", "LPPL-1.1", false, false)
  val `LPPL-1.2` = spdx("LaTeX Project Public License v1.2", "LPPL-1.2", false, true)
  val `LPPL-1.3a` = spdx("LaTeX Project Public License v1.3a", "LPPL-1.3a", false, true)
  val `LPPL-1.3c` = spdx("LaTeX Project Public License v1.3c", "LPPL-1.3c", true, false)
  val MakeIndex: License = spdx("MakeIndex License", "MakeIndex", false, false)
  val MirOS: License = spdx("MirOS License", "MirOS", true, false)
  val `MIT-advertising` = spdx("Enlightenment License (e16)", "MIT-advertising", false, false)
  val `MIT-CMU` = spdx("CMU License", "MIT-CMU", false, false)
  val `MIT-enna` = spdx("enna License", "MIT-enna", false, false)
  val `MIT-feh` = spdx("feh License", "MIT-feh", false, false)
  val MIT: License = spdx("MIT License", "MIT", true, true)
  val MITNFA: License = spdx("MIT +no-false-attribs license", "MITNFA", false, false)
  val Motosoto: License = spdx("Motosoto License", "Motosoto", true, false)
  val mpich2: License = spdx("mpich2 License", "mpich2", false, false)
  val `MPL-1.0` = spdx("Mozilla Public License 1.0", "MPL-1.0", true, false)
  val `MPL-1.1` = spdx("Mozilla Public License 1.1", "MPL-1.1", true, true)
  val `MPL-2.0-no-copyleft-exception` = spdx(
    "Mozilla Public License 2.0 (no copyleft exception)",
    "MPL-2.0-no-copyleft-exception",
    true,
    false
  )
  val `MPL-2.0` = spdx("Mozilla Public License 2.0", "MPL-2.0", true, true)
  val `MS-PL` = spdx("Microsoft Public License", "MS-PL", true, true)
  val `MS-RL` = spdx("Microsoft Reciprocal License", "MS-RL", true, true)
  val MTLL: License = spdx("Matrix Template Library License", "MTLL", false, false)
  val Multics: License = spdx("Multics License", "Multics", true, false)
  val Mup: License = spdx("Mup License", "Mup", false, false)
  val `NASA-1.3` = spdx("NASA Open Source Agreement 1.3", "NASA-1.3", true, false)
  val Naumen: License = spdx("Naumen Public License", "Naumen", true, false)
  val `NBPL-1.0` = spdx("Net Boolean Public License v1", "NBPL-1.0", false, false)
  val NCSA: License = spdx("University of Illinois/NCSA Open Source License", "NCSA", true, true)
  val `Net-SNMP` = spdx("Net-SNMP License", "Net-SNMP", false, false)
  val NetCDF: License = spdx("NetCDF license", "NetCDF", false, false)
  val Newsletr: License = spdx("Newsletr License", "Newsletr", false, false)
  val NGPL: License = spdx("Nethack General Public License", "NGPL", true, false)
  val `NLOD-1.0` = spdx("Norwegian Licence for Open Government Data", "NLOD-1.0", false, false)
  val NLPL: License = spdx("No Limit Public License", "NLPL", false, false)
  val Nokia: License = spdx("Nokia Open Source License", "Nokia", true, true)
  val NOSL: License = spdx("Netizen Open Source License", "NOSL", false, true)
  val Noweb: License = spdx("Noweb License", "Noweb", false, false)
  val `NPL-1.0` = spdx("Netscape Public License v1.0", "NPL-1.0", false, true)
  val `NPL-1.1` = spdx("Netscape Public License v1.1", "NPL-1.1", false, true)
  val `NPOSL-3.0` = spdx("Non-Profit Open Software License 3.0", "NPOSL-3.0", true, false)
  val NRL: License = spdx("NRL License", "NRL", false, false)
  val NTP: License = spdx("NTP License", "NTP", true, false)
  val `OCCT-PL` = spdx("Open CASCADE Technology Public License", "OCCT-PL", false, false)
  val `OCLC-2.0` = spdx("OCLC Research Public License 2.0", "OCLC-2.0", true, false)
  val `ODbL-1.0` = spdx("ODC Open Database License v1.0", "ODbL-1.0", false, true)
  val `OFL-1.0` = spdx("SIL Open Font License 1.0", "OFL-1.0", false, false)
  val `OFL-1.1` = spdx("SIL Open Font License 1.1", "OFL-1.1", true, true)
  val OGTSL: License = spdx("Open Group Test Suite License", "OGTSL", true, false)
  val `OLDAP-1.1` = spdx("Open LDAP Public License v1.1", "OLDAP-1.1", false, false)
  val `OLDAP-1.2` = spdx("Open LDAP Public License v1.2", "OLDAP-1.2", false, false)
  val `OLDAP-1.3` = spdx("Open LDAP Public License v1.3", "OLDAP-1.3", false, false)
  val `OLDAP-1.4` = spdx("Open LDAP Public License v1.4", "OLDAP-1.4", false, false)
  val `OLDAP-2.0.1` = spdx("Open LDAP Public License v2.0.1", "OLDAP-2.0.1", false, false)
  val `OLDAP-2.0` =
    spdx("Open LDAP Public License v2.0 (or possibly 2.0A and 2.0B)", "OLDAP-2.0", false, false)
  val `OLDAP-2.1` = spdx("Open LDAP Public License v2.1", "OLDAP-2.1", false, false)
  val `OLDAP-2.2.1` = spdx("Open LDAP Public License v2.2.1", "OLDAP-2.2.1", false, false)
  val `OLDAP-2.2.2` = spdx("Open LDAP Public License 2.2.2", "OLDAP-2.2.2", false, false)
  val `OLDAP-2.2` = spdx("Open LDAP Public License v2.2", "OLDAP-2.2", false, false)
  val `OLDAP-2.3` = spdx("Open LDAP Public License v2.3", "OLDAP-2.3", false, true)
  val `OLDAP-2.4` = spdx("Open LDAP Public License v2.4", "OLDAP-2.4", false, false)
  val `OLDAP-2.5` = spdx("Open LDAP Public License v2.5", "OLDAP-2.5", false, false)
  val `OLDAP-2.6` = spdx("Open LDAP Public License v2.6", "OLDAP-2.6", false, false)
  val `OLDAP-2.7` = spdx("Open LDAP Public License v2.7", "OLDAP-2.7", false, true)
  val `OLDAP-2.8` = spdx("Open LDAP Public License v2.8", "OLDAP-2.8", false, false)
  val OML: License = spdx("Open Market License", "OML", false, false)
  val OpenSSL: License = spdx("OpenSSL License", "OpenSSL", false, true)
  val `OPL-1.0` = spdx("Open Public License v1.0", "OPL-1.0", false, false)
  val `OSET-PL-2.1` = spdx("OSET Public License version 2.1", "OSET-PL-2.1", true, false)
  val `OSL-1.0` = spdx("Open Software License 1.0", "OSL-1.0", true, true)
  val `OSL-1.1` = spdx("Open Software License 1.1", "OSL-1.1", false, true)
  val `OSL-2.0` = spdx("Open Software License 2.0", "OSL-2.0", true, true)
  val `OSL-2.1` = spdx("Open Software License 2.1", "OSL-2.1", true, true)
  val `OSL-3.0` = spdx("Open Software License 3.0", "OSL-3.0", true, true)
  val `PDDL-1.0` = spdx("ODC Public Domain Dedication & License 1.0", "PDDL-1.0", false, false)
  val `PHP-3.0` = spdx("PHP License v3.0", "PHP-3.0", true, false)
  val `PHP-3.01` = spdx("PHP License v3.01", "PHP-3.01", false, true)
  val Plexus: License = spdx("Plexus Classworlds License", "Plexus", false, false)
  val PostgreSQL: License = spdx("PostgreSQL License", "PostgreSQL", true, false)
  val psfrag: License = spdx("psfrag License", "psfrag", false, false)
  val psutils: License = spdx("psutils License", "psutils", false, false)
  val `Python-2.0` = spdx("Python License 2.0", "Python-2.0", true, true)
  val Qhull: License = spdx("Qhull License", "Qhull", false, false)
  val `QPL-1.0` = spdx("Q Public License 1.0", "QPL-1.0", true, true)
  val Rdisc: License = spdx("Rdisc License", "Rdisc", false, false)
  val `RHeCos-1.1` = spdx("Red Hat eCos Public License v1.1", "RHeCos-1.1", false, false)
  val `RPL-1.1` = spdx("Reciprocal Public License 1.1", "RPL-1.1", true, false)
  val `RPL-1.5` = spdx("Reciprocal Public License 1.5", "RPL-1.5", true, false)
  val `RPSL-1.0` = spdx("RealNetworks Public Source License v1.0", "RPSL-1.0", true, true)
  val `RSA-MD` = spdx("RSA Message-Digest License ", "RSA-MD", false, false)
  val RSCPL: License = spdx("Ricoh Source Code Public License", "RSCPL", true, false)
  val Ruby: License = spdx("Ruby License", "Ruby", false, true)
  val `SAX-PD` = spdx("Sax Public Domain Notice", "SAX-PD", false, false)
  val Saxpath: License = spdx("Saxpath License", "Saxpath", false, false)
  val SCEA: License = spdx("SCEA Shared Source License", "SCEA", false, false)
  val Sendmail: License = spdx("Sendmail License", "Sendmail", false, false)
  val `SGI-B-1.0` = spdx("SGI Free Software License B v1.0", "SGI-B-1.0", false, false)
  val `SGI-B-1.1` = spdx("SGI Free Software License B v1.1", "SGI-B-1.1", false, false)
  val `SGI-B-2.0` = spdx("SGI Free Software License B v2.0", "SGI-B-2.0", false, true)
  val `SimPL-2.0` = spdx("Simple Public License 2.0", "SimPL-2.0", true, false)
  val `SISSL-1.2` = spdx("Sun Industry Standards Source License v1.2", "SISSL-1.2", false, false)
  val SISSL: License = spdx("Sun Industry Standards Source License v1.1", "SISSL", true, false)
  val Sleepycat: License = spdx("Sleepycat License", "Sleepycat", true, true)
  val SMLNJ: License = spdx("Standard ML of New Jersey License", "SMLNJ", false, true)
  val SMPPL: License = spdx("Secure Messaging Protocol Public License", "SMPPL", false, false)
  val SNIA: License = spdx("SNIA Public License 1.1", "SNIA", false, false)
  val `Spencer-86` = spdx("Spencer License 86", "Spencer-86", false, false)
  val `Spencer-94` = spdx("Spencer License 94", "Spencer-94", false, false)
  val `Spencer-99` = spdx("Spencer License 99", "Spencer-99", false, false)
  val `SPL-1.0` = spdx("Sun Public License v1.0", "SPL-1.0", true, true)
  val `SugarCRM-1.1.3` = spdx("SugarCRM Public License v1.1.3", "SugarCRM-1.1.3", false, false)
  val SWL: License =
    spdx("Scheme Widget Library (SWL) Software License Agreement", "SWL", false, false)
  val TCL: License = spdx("TCL/TK License", "TCL", false, false)
  val `TCP-wrappers` = spdx("TCP Wrappers License", "TCP-wrappers", false, false)
  val TMate: License = spdx("TMate Open Source License", "TMate", false, false)
  val `TORQUE-1.1` = spdx("TORQUE v2.5+ Software License v1.1", "TORQUE-1.1", false, false)
  val TOSL: License = spdx("Trusster Open Source License", "TOSL", false, false)
  val `Unicode-DFS-2015` = spdx(
    "Unicode License Agreement - Data Files and Software (2015)",
    "Unicode-DFS-2015",
    false,
    false
  )
  val `Unicode-DFS-2016` = spdx(
    "Unicode License Agreement - Data Files and Software (2016)",
    "Unicode-DFS-2016",
    false,
    false
  )
  val `Unicode-TOU` = spdx("Unicode Terms of Use", "Unicode-TOU", false, false)
  val Unlicense: License = spdx("The Unlicense", "Unlicense", false, true)
  val `UPL-1.0` = spdx("Universal Permissive License v1.0", "UPL-1.0", true, true)
  val Vim: License = spdx("Vim License", "Vim", false, true)
  val VOSTROM: License = spdx("VOSTROM Public License for Open Source", "VOSTROM", false, false)
  val `VSL-1.0` = spdx("Vovida Software License v1.0", "VSL-1.0", true, false)
  val `W3C-19980720` =
    spdx("W3C Software Notice and License (1998-07-20)", "W3C-19980720", false, false)
  val `W3C-20150513` =
    spdx("W3C Software Notice and Document License (2015-05-13)", "W3C-20150513", false, false)
  val W3C: License = spdx("W3C Software Notice and License (2002-12-31)", "W3C", true, true)
  val `Watcom-1.0` = spdx("Sybase Open Watcom Public License 1.0", "Watcom-1.0", true, false)
  val Wsuipa: License = spdx("Wsuipa License", "Wsuipa", false, false)
  val WTFPL: License = spdx("Do What The F*ck You Want To Public License", "WTFPL", false, true)
  val X11: License = spdx("X11 License", "X11", false, true)
  val Xerox: License = spdx("Xerox License", "Xerox", false, false)
  val `XFree86-1.1` = spdx("XFree86 License 1.1", "XFree86-1.1", false, true)
  val xinetd: License = spdx("xinetd License", "xinetd", false, true)
  val Xnet: License = spdx("X.Net License", "Xnet", true, false)
  val xpp: License = spdx("XPP License", "xpp", false, false)
  val XSkat: License = spdx("XSkat License", "XSkat", false, false)
  val `YPL-1.0` = spdx("Yahoo! Public License v1.0", "YPL-1.0", false, false)
  val `YPL-1.1` = spdx("Yahoo! Public License v1.1", "YPL-1.1", false, true)
  val Zed: License = spdx("Zed License", "Zed", false, false)
  val `Zend-2.0` = spdx("Zend License v2.0", "Zend-2.0", false, true)
  val `Zimbra-1.3` = spdx("Zimbra Public License v1.3", "Zimbra-1.3", false, true)
  val `Zimbra-1.4` = spdx("Zimbra Public License v1.4", "Zimbra-1.4", false, false)
  val `zlib-acknowledgement` =
    spdx("zlib/libpng License with Acknowledgement", "zlib-acknowledgement", false, false)
  val Zlib: License = spdx("zlib License", "Zlib", true, true)
  val `ZPL-1.1` = spdx("Zope Public License 1.1", "ZPL-1.1", false, false)
  val `ZPL-2.0` = spdx("Zope Public License 2.0", "ZPL-2.0", true, true)
  val `ZPL-2.1` = spdx("Zope Public License 2.1", "ZPL-2.1", false, true)
  val `AGPL-3.0` = spdx("GNU Affero General Public License v3.0", "AGPL-3.0", true, false)
  val `eCos-2.0` = spdx("eCos license version 2.0", "eCos-2.0", false, false)
  val `GFDL-1.1` = spdx("GNU Free Documentation License v1.1", "GFDL-1.1", false, false)
  val `GFDL-1.2` = spdx("GNU Free Documentation License v1.2", "GFDL-1.2", false, false)
  val `GFDL-1.3` = spdx("GNU Free Documentation License v1.3", "GFDL-1.3", false, false)
  val `GPL-1.0+` = spdx("GNU General Public License v1.0 or later", "GPL-1.0+", false, false)
  val `GPL-1.0` = spdx("GNU General Public License v1.0 only", "GPL-1.0", false, false)
  val `GPL-2.0+` = spdx("GNU General Public License v2.0 or later", "GPL-2.0+", true, false)
  val `GPL-2.0-with-autoconf-exception` = spdx(
    "GNU General Public License v2.0 w/Autoconf exception",
    "GPL-2.0-with-autoconf-exception",
    false,
    false
  )
  val `GPL-2.0-with-bison-exception` = spdx(
    "GNU General Public License v2.0 w/Bison exception",
    "GPL-2.0-with-bison-exception",
    false,
    false
  )
  val `GPL-2.0-with-classpath-exception` = spdx(
    "GNU General Public License v2.0 w/Classpath exception",
    "GPL-2.0-with-classpath-exception",
    false,
    false
  )
  val `GPL-2.0-with-font-exception` = spdx(
    "GNU General Public License v2.0 w/Font exception",
    "GPL-2.0-with-font-exception",
    false,
    false
  )
  val `GPL-2.0-with-GCC-exception` = spdx(
    "GNU General Public License v2.0 w/GCC Runtime Library exception",
    "GPL-2.0-with-GCC-exception",
    false,
    false
  )
  val `GPL-2.0` = spdx("GNU General Public License v2.0 only", "GPL-2.0", true, false)
  val `GPL-3.0+` = spdx("GNU General Public License v3.0 or later", "GPL-3.0+", true, false)
  val `GPL-3.0-with-autoconf-exception` = spdx(
    "GNU General Public License v3.0 w/Autoconf exception",
    "GPL-3.0-with-autoconf-exception",
    false,
    false
  )
  val `GPL-3.0-with-GCC-exception` = spdx(
    "GNU General Public License v3.0 w/GCC Runtime Library exception",
    "GPL-3.0-with-GCC-exception",
    true,
    false
  )
  val `GPL-3.0` = spdx("GNU General Public License v3.0 only", "GPL-3.0", true, false)
  val `LGPL-2.0+` = spdx("GNU Library General Public License v2 or later", "LGPL-2.0+", true, false)
  val `LGPL-2.0` = spdx("GNU Library General Public License v2 only", "LGPL-2.0", true, false)
  val `LGPL-2.1+` = spdx("GNU Library General Public License v2 or later", "LGPL-2.1+", true, false)
  val `LGPL-2.1` = spdx("GNU Lesser General Public License v2.1 only", "LGPL-2.1", true, false)
  val `LGPL-3.0+` =
    spdx("GNU Lesser General Public License v3.0 or later", "LGPL-3.0+", true, false)
  val `LGPL-3.0` = spdx("GNU Lesser General Public License v3.0 only", "LGPL-3.0", true, false)
  val Nunit: License = spdx("Nunit License", "Nunit", false, false)
  val `StandardML-NJ` = spdx("Standard ML of New Jersey License", "StandardML-NJ", false, false)
  val wxWindows: License = spdx("wxWindows Library License", "wxWindows", false, false)

  private def spdx(
      fullName: String,
      id: String,
      isOsiApproved: Boolean,
      isFsfLibre: Boolean
  ): License = License(id, fullName, s"https://spdx.org/licenses/$id.html", isOsiApproved, isFsfLibre, "repo")

  val PublicDomain: License = License(
    id = "Public Domain",
    name = "Public Domain",
    url = "https://creativecommons.org/publicdomain/zero/1.0/",
    isOsiApproved = true, // sort of: https://opensource.org/faq#public-domain
    isFsfLibre = true, // I'm not sure about this
    distribution = "repo"
  )

  val Scala: License = License(
    id = "Scala License",
    name = "Scala License",
    url = "http://www.scala-lang.org/license.html",
    isOsiApproved = false,
    isFsfLibre = false,
    distribution = "repo"
  )

  val TypesafeSubscriptionAgreement: License = License(
    id = "Typesafe Subscription Agreement",
    name = "Typesafe Subscription Agreement",
    url = "http://downloads.typesafe.com/website/legal/TypesafeSubscriptionAgreement.pdf",
    isOsiApproved = false,
    isFsfLibre = false,
    distribution = "repo"
  )

  // https://github.com/sbt/sbt/issues/1937#issuecomment-214963983
  object Common {
    val Apache2 = License.`Apache-2.0`
    val MIT = License.MIT
    val BSD4 = License.`BSD-4-Clause`
    val Typesafe = License.TypesafeSubscriptionAgreement
    val BSD3 = License.`BSD-3-Clause`
  }
}
