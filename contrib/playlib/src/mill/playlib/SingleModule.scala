package mill
package playlib

trait SingleModule extends Module {
  override def millSourcePath: os.Path = super.millSourcePath / os.up
}
