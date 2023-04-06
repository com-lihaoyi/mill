package mill.main

class MillException(msg: String) extends Exception(msg)

class BuildScriptException(msg: String)
    extends MillException("Build script contains errors:\n" + msg)
