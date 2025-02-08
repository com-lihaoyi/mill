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
  def getSteps(millSourcePath: os.Path, provisioner: Provisioner): List[FormatterStep]

  def addLicenseHeaderStep(millSourcePath: os.Path, steps: List[FormatterStep]): Unit = {
    licenseHeader.map(header => LicenseHeaderStep.headerDelimiter(header, licenseHeaderDelimiter))
      .orElse(licenseHeaderFile.map(file => {
        val header: String = os.read(os.Path(getConfFile(millSourcePath, file)))
        LicenseHeaderStep.headerDelimiter(header, licenseHeaderDelimiter)
      }))
      .foreach(step => steps.add(step.build()))
  }

  protected def getConfFile(millSourcePath: os.Path, file: String): File = {
    val configFile: File = Option(new File(file))
      .filter(_.exists())
      .getOrElse(new File(millSourcePath.toIO, file))
    configFile
  }
}

sealed trait JavaFormatter

case class GoogleJavaFormat(
    version: String = "1.25.2",
    aosp: Boolean = false,
    reflowLongStrings: Boolean = false,
    formatJavadoc: Boolean = true,
    reorderImports: Boolean = true,
    groupArtifact: String = "com.google.googlejavaformat:google-java-format"
) extends JavaFormatter { }
object GoogleJavaFormat {
  def apply(): GoogleJavaFormat = new GoogleJavaFormat()
}

case class PalantirJavaFormat(
    version: String = "2.50.0",
    style: String = "PALANTIR",
    formatJavadoc: Boolean = false
) extends JavaFormatter { }
object PalantirJavaFormat {
  def apply(): PalantirJavaFormat = new PalantirJavaFormat()
}

case class JavaConfig(
    target: String = ".java",
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

  def getSteps(millSourcePath: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps: List[FormatterStep] = new ArrayList[FormatterStep]()

    importOrder match {
      case Some(groups) if groups.nonEmpty =>
        steps.add(ImportOrderStep.forJava().createFrom(groups.toArray*))
      case Some(_) =>
        steps.add(ImportOrderStep.forJava().createFrom(Array.empty[String]*))
      case None =>
        importOrderFile.foreach { file =>
          steps.add(ImportOrderStep.forJava().createFrom(getConfFile(millSourcePath, file)))
        }
    }

    if (removeUnusedImports) {
      steps.add(RemoveUnusedImportsStep.create(
        RemoveUnusedImportsStep.defaultFormatter(),
        provisioner
      ))
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

    super.addLicenseHeaderStep(millSourcePath, steps)

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

  def getSteps(millSourcePath: os.Path, provisioner: Provisioner): List[FormatterStep] = {
    val steps = new ArrayList[FormatterStep]()

    scalafmtConfigFile.map(file => {
      val configFile = getConfFile(millSourcePath, file)
      val scalafmtStep = ScalaFmtStep.create(
        scalafmtLibVersion,
        scalafmtScalaMajorVersion,
        provisioner,
        configFile
      )
      steps.add(scalafmtStep)
    })

    super.addLicenseHeaderStep(millSourcePath, steps)

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

  def getSteps(millSourcePath: os.Path, provisioner: Provisioner): List[FormatterStep] = {
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

    super.addLicenseHeaderStep(millSourcePath, steps)

    steps
  }
}
