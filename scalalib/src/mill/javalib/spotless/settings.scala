package mill.javalib.spotless

import java.util.{ArrayList, List}
import java.io.File
import com.diffplug.spotless.FormatterStep
import com.diffplug.spotless.kotlin.{KtfmtStep, KtLintStep}
import com.diffplug.spotless.kotlin.KtfmtStep.KtfmtFormattingOptions
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.java.{GoogleJavaFormatStep, PalantirJavaFormatStep, ImportOrderStep, RemoveUnusedImportsStep, CleanthatJavaStep, FormatAnnotationsStep}
import com.diffplug.spotless.generic.LicenseHeaderStep
import com.diffplug.spotless.Provisioner
import os.Path

trait JVMLangConfig {
  val target: String
  val licenseHeaderDelimiter: String = "(package|import|public|class|module) "
  val licenseHeader: Option[String]
  val licenseHeaderFile: Option[String]
  def getSteps(path: os.Path, provisioner: Provisioner): List[FormatterStep]

  def addLicenseHeaderStep(steps: List[FormatterStep]): Unit = {
    licenseHeader.map(header => LicenseHeaderStep.headerDelimiter(header, licenseHeaderDelimiter))
      .orElse(licenseHeaderFile.map(file => {
        val header: String = os.read(Path(file))
        LicenseHeaderStep.headerDelimiter(header, licenseHeaderDelimiter)
      }))
      .foreach(step => steps.add(step.build()))
  }
}


sealed trait JavaFormatter

case class GoogleJavaFormat private (
                                      version: String = "1.25.2",
                                      aosp: Boolean = false,
                                      reflowLongStrings: Boolean = false,
                                      formatJavadoc: Boolean = true,
                                      reorderImports: Boolean = true,
                                      groupArtifact: String = "com.google.googlejavaformat:google-java-format"
                                    ) extends JavaFormatter {
  def withVersion(version: String): GoogleJavaFormat =
    copy(version = version)

  def withAosp(): GoogleJavaFormat =
    copy(aosp = true)

  def withReflowLongStrings(): GoogleJavaFormat =
    copy(reflowLongStrings = true)

  def withFormatJavadoc(enabled: Boolean): GoogleJavaFormat =
    copy(formatJavadoc = enabled)

  def withReorderImports(enabled: Boolean): GoogleJavaFormat =
    copy(reorderImports = enabled)

  def withGroupArtifact(groupArtifact: String): GoogleJavaFormat =
    copy(groupArtifact = groupArtifact)
}
object GoogleJavaFormat {
  def apply(): GoogleJavaFormat = new GoogleJavaFormat()
}

case class PalantirJavaFormat private (
                                        version: String = "2.50.0",
                                        style: String = "PALANTIR",
                                        formatJavadoc: Boolean = false,
                                        ) extends JavaFormatter {
  def withVersion(version: String): PalantirJavaFormat =
    copy(version = version)

  def withStyle(style: String): PalantirJavaFormat =
    copy(style = style)

  def withFormatJavadoc(enabled: Boolean): PalantirJavaFormat =
    copy(formatJavadoc = enabled)
}
object PalantirJavaFormat {
  def apply(): PalantirJavaFormat = new PalantirJavaFormat()
}


case class JavaConfig (target: String = ".java",
                       licenseHeader: Option[String] = None,
                       licenseHeaderFile: Option[String] = None,
                       importOrder: Option[Seq[String]] = None,
                      importOrderFile: Option[String] = None,
                      removeUnusedImports: Boolean = false,
                      cleanthat: Boolean = false,
                      formatter: Option[JavaFormatter] = None,
                      formatAnnotations: Boolean = false
                    ) extends JVMLangConfig {
  require(
    !(importOrder.isDefined && importOrderFile.isDefined),
    "Please specify only importOrder or importOrderFile but not both"
  )

  require(
    !(licenseHeader.isDefined && licenseHeaderFile.isDefined),
    "Please specify only licenseHeader or licenseHeaderFile but not both"
  )

  def withDefaultImportOrder(): JavaConfig =
    copy(importOrder = Some(Seq()))

  def withImportOrder(groups: String*): JavaConfig =
    copy(importOrder = Some(groups.toSeq))

  def withImportOrderFile(file: String): JavaConfig =
    copy(importOrderFile = Some(file))

  def withRemoveUnusedImports(): JavaConfig =
    copy(removeUnusedImports = true)

  def withCleanthat(): JavaConfig =
    copy(cleanthat = true)

  def withLicenseHeader(header: String): JavaConfig =
    copy(licenseHeader = Some(header))

  def withLicenseHeaderFile(file: String): JavaConfig =
    copy(licenseHeaderFile = Some(file))

  def withJavaFormat(formatter: JavaFormatter): JavaConfig =
    copy(formatter = Some(formatter))


  def getSteps(path: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps: List[FormatterStep] = new ArrayList[FormatterStep]()

    importOrder match {
      case Some(groups) if groups.nonEmpty =>
        steps.add(ImportOrderStep.forJava().createFrom(groups.toArray: _*))
      case Some(_) =>
        steps.add(ImportOrderStep.forJava().createFrom(Array.empty[String]: _*))
      case None =>
        importOrderFile.foreach { file =>
          steps.add(ImportOrderStep.forJava().createFrom(os.Path(file).toIO))
        }
    }

    if (removeUnusedImports) {
      steps.add(RemoveUnusedImportsStep.create(RemoveUnusedImportsStep.defaultFormatter(), provisioner))
    }

    if (cleanthat) {
      steps.add(CleanthatJavaStep.create(provisioner))
    }

    formatter.foreach {
      case gf: GoogleJavaFormat =>
        val step = GoogleJavaFormatStep.create(
          gf.groupArtifact,
          gf.version,
          if (gf.aosp) "AOSP" else "GOOGLE",
          provisioner,
          gf.reflowLongStrings,
          gf.reorderImports,
          gf.formatJavadoc
        )
        steps.add(step)

      case pjf: PalantirJavaFormat =>
        val step = PalantirJavaFormatStep.create(pjf.version, pjf.style, provisioner)
        steps.add(step)
    }

    if (formatAnnotations) {
      steps.add(FormatAnnotationsStep.create())
    }

    super.addLicenseHeaderStep(steps)

    steps
  }
}

case class ScalaConfig(
                        target: String = ".scala",
                        licenseHeader: Option[String] = None,
                        licenseHeaderFile: Option[String] = None,
                        scalafmtLibVersion: String = "3.8.1",
                        scalafmtConfigFile: Option[String] = None,
                        scalafmtScalaMajorVersion: String = "2.13") extends JVMLangConfig {
  require(
    !(licenseHeader.isDefined && licenseHeaderFile.isDefined),
    "Please specify only licenseHeader or licenseHeaderFile but not both"
  )

  def getSteps(path: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps = new ArrayList[FormatterStep]()

    scalafmtConfigFile.map(file => {
      val scalafmtStep = ScalaFmtStep.create(scalafmtLibVersion, scalafmtScalaMajorVersion, provisioner, new File(path.toIO, file))
      steps.add(scalafmtStep)
    })

    super.addLicenseHeaderStep(steps)

    steps
  }
}


case class KotlinConfig(
                         target: String = ".kt",
                         licenseHeader: Option[String] = None,
                         licenseHeaderFile: Option[String] = None,
                         override val licenseHeaderDelimiter: String = "(package |@file|import )",
                         ktfmtVersion: String = "0.53",
                         ktfmtOptions: Option[Map[String, Any]] = None,
                         klintVersion: String = "1.5.0") extends JVMLangConfig {
  require(
    !(licenseHeader.isDefined && licenseHeaderFile.isDefined),
    "Please specify only licenseHeader or licenseHeaderFile but not both"
  )

  def withKtfmtVersion(version: String): KotlinConfig =
    copy(ktfmtVersion = version)

  def withKtfmtOptions(options: Map[String, Any]): KotlinConfig =
    copy(ktfmtOptions = Some(options))

  def withKtlintVersion(version: String): KotlinConfig =
    copy(klintVersion = version)


  def getSteps(path: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps = new ArrayList[FormatterStep]()

    if (ktfmtOptions.isDefined) {
      val options = new KtfmtFormattingOptions(
        ktfmtOptions.get("maxWidth") match { case Some(v: Int) => v; case _ => null },
        ktfmtOptions.get("blockIndent") match { case Some(v: Int) => v; case _ => null },
        ktfmtOptions.get("continuationIndent") match { case Some(v: Int) => v; case _ => null },
        ktfmtOptions.get("removeUnusedImports") match { case Some(v: Boolean) => v; case _ => null },
        ktfmtOptions.get("manageTrailingCommas") match { case Some(v: Boolean) => v; case _ => null }
      )
      val step = KtfmtStep.create(ktfmtVersion, provisioner, null, options)
      steps.add(step)
    }

    val step = KtLintStep.create(klintVersion, provisioner)
    steps.add(step)

    super.addLicenseHeaderStep(steps)

    steps
  }
}
