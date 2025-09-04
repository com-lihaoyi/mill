package mill.scalajslib.api

import upickle.ReadWriter

enum ModuleKind derives ReadWriter {
  case NoModule
  case CommonJSModule
  case ESModule
}
