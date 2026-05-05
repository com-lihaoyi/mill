package hello

abstract class Root {
  onInit()
  def onInit(): Unit

  // Another overridable method on Root that only Bar implements locally
  def jdkCommandsJavaHome(): String = "root"
}
