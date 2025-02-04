package mill.util

import mill.api.Result

object ProcessUtil {

  def toResult(processResult: os.CommandResult): Result[Int] = {
    if (processResult.exitCode == 0) Result.Success(processResult.exitCode)
    else Result.Failure(
      "Interactive Subprocess Failed (exit code " + processResult.exitCode + ")",
      Some(processResult.exitCode)
    )
  }

}
