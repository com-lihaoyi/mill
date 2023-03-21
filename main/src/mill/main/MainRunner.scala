//package mill.main
//import java.io.{InputStream, PrintStream}
//
//import ammonite.Main
//import ammonite.interp.Interpreter
//import ammonite.compiler.iface.Preprocessor
//import ammonite.util.Util.CodeSource
//import ammonite.util._
//import mill.eval.Evaluator
//import mill.api.PathRef
//import mill.util.PrintLogger
//
//import scala.annotation.tailrec
//import ammonite.runtime.ImportHook
//import mill.define.Segments
//
///**
// * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
// * `build.sc` scripts with mill-specific tweaks such as a custom
// * `scriptCodeWrapper` or with a persistent evaluator between runs.
// */
//class MainRunner(
//    val config: ammonite.main.Config,
//    mainInteractive: Boolean,
//    disableTicker: Boolean,
//    outprintStream: PrintStream,
//    errPrintStream: PrintStream,
//    stdIn: InputStream,
//    stateCache0: Option[EvaluatorState] = None,
//    env: Map[String, String],
//    setIdle: Boolean => Unit,
//    debugLog: Boolean,
//    keepGoing: Boolean,
//    systemProperties: Map[String, String],
//    threadCount: Option[Int],
//    ringBell: Boolean,
//    wd: os.Path,
//    initialSystemProperties: Map[String, String]
//) extends ammonite.MainRunner(
//      cliConfig = config,
//      outprintStream = outprintStream,
//      errPrintStream = errPrintStream,
//      stdIn = stdIn,
//      stdOut = outprintStream,
//      stdErr = errPrintStream,
//      wd = wd
//    ) {
//
//  var stateCache = stateCache0
//
//  override def watchAndWait(watched: Seq[(mill.internal.Watchable, Long)]) = {
//    setIdle(true)
//    super.watchAndWait(watched)
//    setIdle(false)
//  }
//
//  /**
//   * Custom version of [[watchLoop]] that lets us generate the watched-file
//   * signature only on demand, so if we don't have config.watch enabled we do
//   * not pay the cost of generating it
//   */
//  @tailrec final def watchLoop2[T](
//      isRepl: Boolean,
//      printing: Boolean,
//      run: Main => (Res[T], () => Seq[(mill.internal.Watchable, Long)])
//  ): Boolean = {
//    val (result, watched) = run(initMain(isRepl))
//
//    val success = handleWatchRes(result, printing)
//    if (ringBell) {
//      if (success) println("\u0007")
//      else {
//        println("\u0007")
//        Thread.sleep(250)
//        println("\u0007")
//      }
//    }
//    if (!config.core.watch.value) success
//    else {
//      watchAndWait(watched())
//      watchLoop2(isRepl, printing, run)
//    }
//  }
//
//  val colored = config.core.color.getOrElse(mainInteractive)
//
//  override val colors = if (colored) Colors.Default else Colors.BlackWhite
//  override def runScript(scriptPath: os.Path, scriptArgs: List[String]): Boolean =
//    watchLoop2(
//      isRepl = false,
//      printing = true,
//      mainCfg => {
//        val logger = PrintLogger(
//          colored = colored,
//          disableTicker = disableTicker,
//          infoColor = colors.info(),
//          errorColor = colors.error(),
//          outStream = outprintStream,
//          infoStream = errPrintStream,
//          errStream = errPrintStream,
//          inStream = stdIn,
//          debugEnabled = debugLog,
//          context = ""
//        )
//        logger.debug(s"Using explicit system properties: ${systemProperties}")
//
//        val (result, interpWatched) = RunScript.runScript(
//          home = config.core.home,
//          wd = mainCfg.wd,
//          path = scriptPath,
//          instantiateInterpreter = mainCfg.instantiateInterpreter(),
//          scriptArgs = scriptArgs,
//          stateCache = stateCache,
//          log = logger,
//          env = env,
//          keepGoing = keepGoing,
//          systemProperties = systemProperties,
//          threadCount = threadCount,
//          initialSystemProperties = initialSystemProperties
//        )
//
//        result match {
//          case Res.Success(data) =>
//            val (eval, evalWatches, res) = data
//
//            stateCache = Some(EvaluatorState(
//              rootModule = eval.rootModule,
//              classLoaderSig = eval.classLoaderSig,
//              workerCache = eval.workerCache,
//              watched = interpWatched,
//              setSystemProperties = systemProperties.keySet,
//              importTree = eval.importTree
//            ))
//            val watched = () => {
//              val alreadyStale = evalWatches.exists(p => p.sig != PathRef(p.path, p.quick).sig)
//              // If the file changed between the creation of the original
//              // `PathRef` and the current moment, use random junk .sig values
//              // to force an immediate re-run. Otherwise calculate the
//              // pathSignatures the same way Ammonite would and hand over the
//              // values, so Ammonite can watch them and only re-run if they
//              // subsequently change
//              if (alreadyStale) evalWatches.map(p =>
//                (mill.internal.Watchable.Path(p.path), util.Random.nextLong())
//              )
//              else evalWatches.map(p =>
//                (
//                  mill.internal.Watchable.Path(p.path),
//                  mill.internal.Watchable.pathSignature(p.path)
//                )
//              )
//            }
//            (Res(res), () => interpWatched ++ watched())
//          case _ => (result, () => interpWatched)
//        }
//      }
//    )
//
//  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
//    res match {
//      case Res.Success(value) => true
//      case _ => super.handleWatchRes(res, printing)
//    }
//  }
//
//  override def initMain(isRepl: Boolean): Main = {
//    val hooks = ImportHook.defaults + (Seq("ivy") -> MillIvyHook)
//    super.initMain(isRepl).copy(
//      scriptCodeWrapper = CustomCodeWrapper,
//      // Ammonite does not properly forward the wd from CliConfig to Main, so
//      // force forward it outselves
//      wd = wd,
//      importHooks = hooks
//    )
//  }
//
//  object CustomCodeWrapper extends ammonite.compiler.iface.CodeWrapper {
//    def apply(
//        code: String,
//        source: CodeSource,
//        imports: ammonite.util.Imports,
//        printCode: String,
//        indexedWrapperName: ammonite.util.Name,
//        extraCode: String
//    ): (String, String, Int) = {
//      import source.pkgName
//      val wrapName = indexedWrapperName.backticked
//      val path = source.path
//        .map(_ / os.up)
//        .getOrElse(wd)
//      val literalPath = pprint.Util.literalize(path.toString)
//      val foreignPath = path / wrapName
//      val foreign =
//        if (foreignPath != wd / "build") {
//          // Computing a path in "out" that uniquely reflects the location
//          // of the foreign module relatively to the current build.
//          val relative = foreignPath.relativeTo(wd)
//          // Encoding the number of `/..`
//          val ups = if (relative.ups > 0) Seq(s"up-${relative.ups}") else Seq()
//          val segs = Seq("foreign-modules") ++ ups ++ relative.segments
//          val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
//          s"Some(_root_.mill.define.Segments.labels($segsList))"
//        } else "None"
//
//      val top =
//        s"""
//           |package ${pkgName.head.encoded}
//           |package ${Util.encodeScalaSourcePath(pkgName.tail)}
//           |$imports
//           |import _root_.mill._
//           |object $wrapName
//           |extends _root_.mill.define.BaseModule(os.Path($literalPath), foreign0 = $foreign)(
//           |  implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())
//           |)
//           |with $wrapName{
//           |  // Stub to make sure Ammonite has something to call after it evaluates a script,
//           |  // even if it does nothing...
//           |  def $$main() = Iterator[String]()
//           |
//           |  // Need to wrap the returned Module in Some(...) to make sure it
//           |  // doesn't get picked up during reflective child-module discovery
//           |  def millSelf = Some(this)
//           |
//           |  @_root_.scala.annotation.nowarn("cat=deprecation")
//           |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
//           |}
//           |
//           |sealed trait $wrapName extends _root_.mill.main.MainModule{
//           |""".stripMargin
//      val bottom = "\n}"
//
//      (top, bottom, 1)
//    }
//  }
//}
