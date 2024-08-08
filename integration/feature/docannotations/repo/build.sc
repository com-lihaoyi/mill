import mill._
import mill.scalalib._

trait JUnitTests extends TestModule.Junit4 {

  /**
   * Overridden ivyDeps Docs!!!
   */
  def ivyDeps = Agg(ivy"com.novocode:junit-interface:0.11")
  def task = Task {
    "???"
  }
}

/**
 * The Core Module Docz!
 */
object core extends JavaModule {
  object test extends JavaTests with JUnitTests

  /**
   * Core Target Docz!
   */
  def target = Task {
    import collection.JavaConverters._
    println(this.getClass.getClassLoader.getResources("scalac-plugin.xml").asScala.toList)
    "Hello!"
  }
}
