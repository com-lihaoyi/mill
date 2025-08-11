package mill.scalajslib.api

import upickle.default.ReadWriter

enum ModuleKind derives ReadWriter {
  case NoModule
  case CommonJSModule
  case ESModule
}
