package mill.kotlinlib.js

import upickle.default.ReadWriter

private[kotlinlib] enum OutputMode derives ReadWriter {
  case Js
  case KlibDir
  case KlibFile
}
