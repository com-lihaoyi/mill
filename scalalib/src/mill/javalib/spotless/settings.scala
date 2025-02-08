package mill.javalib.spotless

import java.util.{ArrayList, List}
import java.io.File
import com.diffplug.spotless.FormatterStep
import com.diffplug.spotless.kotlin.{KtfmtStep, KtLintStep}
import com.diffplug.spotless.kotlin.KtfmtStep.KtfmtFormattingOptions
import com.diffplug.spotless.scala.ScalaFmtStep
import com.diffplug.spotless.java.{
  GoogleJavaFormatStep,
  PalantirJavaFormatStep,
  ImportOrderStep,
  RemoveUnusedImportsStep,
  CleanthatJavaStep,
  FormatAnnotationsStep
}
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
    aospFlag: Boolean = false,
    reflowLongStringsFlag: Boolean = false,
    formatJavadocFlag: Boolean = true,
    reorderImportsFlag: Boolean = true,
    groupArtifact: String = "com.google.googlejavaformat:google-java-format"
) extends JavaFormatter {
  def version(version: String): GoogleJavaFormat =
    copy(version = version)

  def aosp(): GoogleJavaFormat =
    copy(aospFlag = true)

  def reflowLongStrings(): GoogleJavaFormat =
    copy(reflowLongStringsFlag = true)

  def formatJavadoc(enabled: Boolean): GoogleJavaFormat =
    copy(formatJavadocFlag = enabled)

  def reorderImports(enabled: Boolean): GoogleJavaFormat =
    copy(reorderImportsFlag = enabled)

  def groupArtifact(groupArtifact: String): GoogleJavaFormat =
    copy(groupArtifact = groupArtifact)
}
object GoogleJavaFormat {
  def apply(): GoogleJavaFormat = new GoogleJavaFormat()
}

case class PalantirJavaFormat private (
    version: String = "2.50.0",
    style: String = "PALANTIR",
    formatJavadocFlag: Boolean = false
) extends JavaFormatter {
  def version(version: String): PalantirJavaFormat =
    copy(version = version)

  def style(style: String): PalantirJavaFormat =
    copy(style = style)

  def formatJavadoc(enabled: Boolean): PalantirJavaFormat =
    copy(formatJavadocFlag = enabled)
}
object PalantirJavaFormat {
  def apply(): PalantirJavaFormat = new PalantirJavaFormat()
}

case class JavaConfig(
    target: String = ".java",
    licenseHeader: Option[String] = None,
    licenseHeaderFile: Option[String] = None,
    importOrder: Option[Seq[String]] = None,
    importOrderFile: Option[String] = None,
    removeUnusedImportsFlag: Boolean = false,
    cleanthatFlag: Boolean = false,
    formatter: Option[JavaFormatter] = None,
    formatAnnotationsFlag: Boolean = false
) extends JVMLangConfig {
  require(
    !(importOrder.isDefined && importOrderFile.isDefined),
    "Please specify only importOrder or importOrderFile but not both"
  )

  require(
    !(licenseHeader.isDefined && licenseHeaderFile.isDefined),
    "Please specify only licenseHeader or licenseHeaderFile but not both"
  )

  def defaultImportOrder(): JavaConfig =
    copy(importOrder = Some(Seq()))

  def importOrder(groups: String*): JavaConfig =
    copy(importOrder = Some(groups.toSeq))

  def importOrderFile(file: String): JavaConfig =
    copy(importOrderFile = Some(file))

  def removeUnusedImports(): JavaConfig =
    copy(removeUnusedImportsFlag = true)

  def cleanthat(): JavaConfig =
    copy(cleanthatFlag = true)

  def licenseHeader(header: String): JavaConfig =
    copy(licenseHeader = Some(header))

  def licenseHeaderFile(file: String): JavaConfig =
    copy(licenseHeaderFile = Some(file))

  def javaFormat(formatter: JavaFormatter): JavaConfig =
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

    if (removeUnusedImportsFlag) {
      steps.add(RemoveUnusedImportsStep.create(
        RemoveUnusedImportsStep.defaultFormatter(),
        provisioner
      ))
    }

    if (cleanthatFlag) {
      steps.add(CleanthatJavaStep.create(provisioner))
    }

    formatter.foreach {
      case gf: GoogleJavaFormat =>
        val step = GoogleJavaFormatStep.create(
          gf.groupArtifact,
          gf.version,
          if (gf.aospFlag) "AOSP" else "GOOGLE",
          provisioner,
          gf.reflowLongStringsFlag,
          gf.reorderImportsFlag,
          gf.formatJavadocFlag
        )
        steps.add(step)

      case pjf: PalantirJavaFormat =>
        val step = PalantirJavaFormatStep.create(pjf.version, pjf.style, provisioner)
        steps.add(step)
    }

    if (formatAnnotationsFlag) {
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
    scalafmtScalaMajorVersion: String = "2.13"
) extends JVMLangConfig {
  require(
    !(licenseHeader.isDefined && licenseHeaderFile.isDefined),
    "Please specify only licenseHeader or licenseHeaderFile but not both"
  )

  def getSteps(path: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps = new ArrayList[FormatterStep]()

    scalafmtConfigFile.map(file => {
      val scalafmtStep = ScalaFmtStep.create(
        scalafmtLibVersion,
        scalafmtScalaMajorVersion,
        provisioner,
        new File(path.toIO, file)
      )
      steps.add(scalafmtStep)
    })

    super.addLicenseHeaderStep(steps)

    steps
  }
}

case class KtfmtOptions(
    maxWidth: Option[Int] = Some(80),
    blockIndent: Option[Int] = Some(4),
    continuationIndent: Option[Int] = Some(4),
    removeUnusedImports: Option[Boolean] = Some(false),
    manageTrailingCommas: Option[Boolean] = Some(false)
)
case class KotlinConfig(
    target: String = ".kt",
    licenseHeader: Option[String] = None,
    licenseHeaderFile: Option[String] = None,
    override val licenseHeaderDelimiter: String = "(package |@file|import )",
    ktfmtFlag: Boolean = false,
    ktfmtVersion: String = "0.53",
    ktfmtOptions: Option[KtfmtOptions] = None,
    ktlintFlag: Boolean = false,
    ktlintVersion: String = "1.5.0"
) extends JVMLangConfig {
  require(
    !(licenseHeader.isDefined && licenseHeaderFile.isDefined),
    "Please specify only licenseHeader or licenseHeaderFile but not both"
  )

  def ktfmt(): KotlinConfig =
    copy(ktfmtFlag = true)

  def ktfmt(version: String): KotlinConfig =
    copy(ktfmtFlag = true, ktfmtVersion = version)

  def ktfmtOptions(options: KtfmtOptions): KotlinConfig =
    copy(ktfmtFlag = true, ktfmtOptions = Some(options))

  def ktlint(): KotlinConfig =
    copy(ktlintFlag = true)

  def ktlint(version: String): KotlinConfig =
    copy(ktlintFlag = true, ktlintVersion = version)

  def getSteps(path: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps = new ArrayList[FormatterStep]()

    if (ktfmtFlag) {
      var options: KtfmtFormattingOptions = null
      if (ktfmtOptions.isDefined) {
        options = new KtfmtFormattingOptions(
          ktfmtOptions.flatMap(_.maxWidth).map(Integer.valueOf).orNull,
          ktfmtOptions.flatMap(_.blockIndent).map(Integer.valueOf).orNull,
          ktfmtOptions.flatMap(_.continuationIndent).map(Integer.valueOf).orNull,
          ktfmtOptions.flatMap(_.removeUnusedImports).map(Boolean.box).orNull,
          ktfmtOptions.flatMap(_.manageTrailingCommas).map(Boolean.box).orNull
        )
      }
      val step = KtfmtStep.create(ktfmtVersion, provisioner, null, options)
      steps.add(step)
    }

    if (ktlintFlag) {
      val step = KtLintStep.create(ktlintVersion, provisioner)
      steps.add(step)
    }

    super.addLicenseHeaderStep(steps)

    steps
  }
}
