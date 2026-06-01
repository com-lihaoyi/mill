package mill.exec

import mill.api.{BuildCtx, SystemStreams, SystemStreamsUtils}
import mill.api.daemon.Watchable
import mill.api.internal.PathAliasing

import scala.collection.mutable

private[exec] final case class TaskThreadContext(
    pwd: () => os.Path,
    checker: os.Checker,
    spawnHook: os.Path => Unit,
    subProcessEnv: Map[String, String],
    streams: SystemStreams,
    moduleWatched: mutable.Buffer[Watchable],
    evalWatched: mutable.Buffer[Watchable]
) {
  def bind[T](body: => T): T =
    os.checker.withValue(checker) {
      os.dynamicPwdFunction.withValue(pwd) {
        os.ProcessOps.spawnHook.withValue(spawnHook) {
          os.SubProcess.env.withValue(subProcessEnv) {
            SystemStreamsUtils.withStreams(streams) {
              BuildCtx.watchedValues0.withValue(moduleWatched) {
                BuildCtx.evalWatchedValues0.withValue(evalWatched) {
                  body
                }
              }
            }
          }
        }
      }
    }
}

private[exec] object TaskThreadContext {
  def capture(
      pwd: () => os.Path = os.dynamicPwdFunction.value,
      checker: os.Checker = os.checker.value,
      streams: SystemStreams = SystemStreams(Console.out, Console.err, System.in)
  ): TaskThreadContext = TaskThreadContext(
    pwd = pwd,
    checker = checker,
    spawnHook = os.ProcessOps.spawnHook.value,
    subProcessEnv = Option(os.SubProcess.env.value)
      .getOrElse(PathAliasing.subprocessBaseEnv(sys.env)),
    streams = streams,
    moduleWatched = BuildCtx.watchedValues0.value,
    evalWatched = BuildCtx.evalWatchedValues0.value
  )
}
