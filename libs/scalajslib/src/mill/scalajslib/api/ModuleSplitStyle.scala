package mill.scalajslib.api

import upickle.ReadWriter

enum ModuleSplitStyle derives ReadWriter {
  case FewestModules
  case SmallestModules
  case SmallModulesFor(packages: String*)
}
