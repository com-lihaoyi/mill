package mill.kotlinlib.js

import upickle.ReadWriter

enum BinaryKind derives ReadWriter {
  case Library
  case Executable
}
