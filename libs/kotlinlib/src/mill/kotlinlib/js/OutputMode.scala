package mill.kotlinlib.js

import upickle.ReadWriter

private[kotlinlib] enum OutputMode derives ReadWriter {
  case Js
  case KlibDir
  case KlibFile
}
