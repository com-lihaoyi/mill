package mill.init.migrate

import geny.Generator
import upickle.default.{ReadWriter, macroRW}

case class MetaModule[A](
    moduleDir: Seq[String],
    metadata: A,
    name: String = MetaModule.packageModuleName,
    data: ModuleData = ModuleData(),
    submodules: Seq[MetaModule[A]] = Nil
) {

  def isEmbed = moduleDir.isEmpty && !isPackage

  def isPackage = name == MetaModule.packageModuleName

  def isRoot = moduleDir.isEmpty && isPackage

  def nameAlias = if (isPackage) moduleDir.lastOption.getOrElse(os.pwd.last) else name

  def stream: Generator[MetaModule[A]] = handleItem => {
    def recurse(module: MetaModule[A]): Generator.Action = {
      var last: Generator.Action = Generator.Continue
      var index = 0
      while (last == Generator.Continue && index < module.submodules.length) {
        last = recurse(module.submodules(index))
        index += 1
      }
      if (last == Generator.Continue) handleItem(module) else last
    }
    recurse(this)
  }

  def transform[B](f: (MetaModule[A], Seq[MetaModule[B]]) => MetaModule[B]) = {
    def recurse(module: MetaModule[A]): MetaModule[B] =
      f(module, module.submodules.iterator.map(recurse).toSeq)
    recurse(this)
  }
}
object MetaModule {
  val packageModuleName = "package"
  implicit def rw[T: ReadWriter]: ReadWriter[MetaModule[T]] = macroRW
}
