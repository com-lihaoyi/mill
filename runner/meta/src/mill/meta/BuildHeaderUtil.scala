package mill.meta

import mill.api.Result

object BuildHeaderUtil {
  private val AllowNestedKey = mill.constants.ConfigConstants.allowNestedBuildMillFiles

  def allowNestedBuildMillFiles(path: os.Path): Boolean = {
    mill.internal.Util.parseHeaderData(path) match {
      case Result.Success(headerData) =>
        headerData.rest.collectFirst {
          case (key, upickle.core.BufferedValue.Str(value, _))
              if key.value == AllowNestedKey =>
            value == "true"
        }.getOrElse(false)
      case _: Result.Failure => false
    }
  }
}
