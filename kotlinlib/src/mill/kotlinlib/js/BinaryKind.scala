package mill.kotlinlib.js

import upickle.default.ReadWriter

enum BinaryKind derives ReadWriter {
  case Library
  case Executable
}
