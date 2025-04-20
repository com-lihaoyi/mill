package mill.kotlinlib.js

import upickle.default.ReadWriter

enum ModuleKind(val extension: String) derives ReadWriter {
  case NoModule extends ModuleKind("js")
  case UMDModule extends ModuleKind("js")
  case CommonJSModule extends ModuleKind("js")
  case AMDModule extends ModuleKind("js")
  case ESModule extends ModuleKind("mjs")
  case PlainModule extends ModuleKind("js")
}
