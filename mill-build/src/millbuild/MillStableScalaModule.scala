package millbuild

import mill._
import mill.scalalib._
import com.github.lolgab.mill.mima.Mima

/** Publishable module which contains strictly handled API. */
trait MillStableScalaModule extends MillPublishScalaModule with Mima {
  import com.github.lolgab.mill.mima._
  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq(
    // (5x) MIMA doesn't properly ignore things which are nested inside other private things
    // so we have to put explicit ignores here (https://github.com/lightbend/mima/issues/771)
    ProblemFilter.exclude[Problem]("mill.eval.ProfileLogger*"),
    ProblemFilter.exclude[Problem]("mill.eval.ChromeProfileLogger*"),
    ProblemFilter.exclude[Problem]("mill.eval.GroupEvaluator*"),
    ProblemFilter.exclude[Problem]("mill.eval.EvaluatorCore*"),
    ProblemFilter.exclude[Problem]("mill.eval.Tarjans*"),
    ProblemFilter.exclude[Problem]("mill.define.Ctx#Impl*"),
    ProblemFilter.exclude[Problem]("mill.resolve.ResolveNotFoundHandler*"),
    // (4x) See https://github.com/com-lihaoyi/mill/pull/2739
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalajslib.ScalaJSModule.mill$scalajslib$ScalaJSModule$$super$scalaLibraryIvyDeps"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.ScalaModule.mill$scalalib$ScalaModule$$super$zincAuxiliaryClassFileExtensions"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalajslib.ScalaJSModule.mill$scalajslib$ScalaJSModule$$super$zincAuxiliaryClassFileExtensions"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalanativelib.ScalaNativeModule.mill$scalanativelib$ScalaNativeModule$$super$zincAuxiliaryClassFileExtensions"
    ),
    // (6x) See https://github.com/com-lihaoyi/mill/pull/3064
    // Moved targets up in trait hierarchy, but also call them via super, which I think is safe
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$zincWorker"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runClasspath"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runUseArgsFile"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$forkArgs"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$forkEnv"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$forkWorkingDir"
    ),
    // (8x)
    // Moved targets up in trait hierarchy, but also call them via super, which I think is safe
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$localRunClasspath"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runLocal"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$run"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$doRunBackground"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runBackgroundLogToConsole"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runMainBackground"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runMainLocal"
    ),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.JavaModule.mill$scalalib$JavaModule$$super$runMain"
    ),
    // Terminal is sealed, not sure why MIMA still complains
    ProblemFilter.exclude[ReversedMissingMethodProblem]("mill.eval.Terminal.task"),

    // Not sure why mima is picking up this stuff which is private[mill]
    ProblemFilter.exclude[Problem]("mill.resolve.*.resolve0"),
    ProblemFilter.exclude[Problem]("mill.resolve.*.resolveRootModule"),

    // These methods are private so it doesn't matter
    ProblemFilter.exclude[ReversedMissingMethodProblem]("mill.resolve.Resolve.handleResolved"),
    ProblemFilter.exclude[Problem]("mill.resolve.*.resolveNonEmptyAndHandle*"),
    ProblemFilter.exclude[Problem]("mill.resolve.ResolveCore*"),
    ProblemFilter.exclude[InheritedNewAbstractMethodProblem](
      "mill.main.MainModule.mill$define$BaseModule0$_setter_$watchedValues_="
    ),
    ProblemFilter.exclude[InheritedNewAbstractMethodProblem](
      "mill.main.MainModule.mill$define$BaseModule0$_setter_$evalWatchedValues_="
    ),

    // https://github.com/com-lihaoyi/mill/pull/3503
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.ScalaModule#ScalaTests.mill$scalalib$ScalaModule$ScalaTests$$super$mandatoryScalacOptions"
    ),
    // Not sure why Mima is complaining when these are internal and private
    ProblemFilter.exclude[Problem]("*.bspJvmBuildTarget"),
    ProblemFilter.exclude[Problem]("mill.scalalib.RunModule#RunnerImpl.*"),
    ProblemFilter.exclude[Problem]("mill.util.PromptLogger#*"),
    ProblemFilter.exclude[Problem]("mill.util.PromptLoggerUtil.*"),
    ProblemFilter.exclude[Problem]("mill.main.SelectiveExecution*")
  )
  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaPreviousArtifacts: T[Agg[Dep]] = Task {
    Agg.from(
      Settings.mimaBaseVersions
        .filter(v => !skipPreviousVersions().contains(v))
        .map({ version =>
          val patchedSuffix = {
            val base = artifactSuffix()
            version match {
              case s"0.$minor.$_" if minor.toIntOption.exists(_ < 12) =>
                base match {
                  case "_3" => "_2.13"
                  case s"_3_$suffix" => s"_2.13_$suffix"
                  case _ => base
                }
              case _ => base
            }
          }
          val patchedId = artifactName() + patchedSuffix
          ivy"${pomSettings().organization}:${patchedId}:${version}"
        })
    )
  }

  def mimaExcludeAnnotations = Seq("mill.api.internal", "mill.api.experimental")
//  def mimaCheckDirection = CheckDirection.Backward
  def skipPreviousVersions: T[Seq[String]] = T {
    T.log.info("Skipping mima for previous versions (!!1000s of errors due to Scala 3)")
    mimaPreviousVersions() // T(Seq.empty[String])
  }
}
