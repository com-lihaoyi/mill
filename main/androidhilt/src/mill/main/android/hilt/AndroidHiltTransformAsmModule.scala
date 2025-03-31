package mill.main.android.hilt

import mill.*

object AndroidHiltTransformAsmModule {

  def main(args: Array[String]): Unit = {

    val scanDirectory = os.Path(args.head)

    val destination = os.Path(args.last)

    transformAsm(os.walk(scanDirectory).filter(_.ext == "class"), destination)

  }

  def transformAsm(classes: Seq[os.Path], destination: os.Path): Seq[os.Path] = {

    os.makeDir.all(destination)

    classes.map {
      path =>
        if (shouldTransform(destination))
          transform(path, destination)
        else {
          val fileDest = destination / path.last
          os.copy(path, fileDest, createFolders = true)
          fileDest
        }
    }

  }

  private def shouldTransform(`class`: os.Path): Boolean = false

  private def transform(`class`: os.Path, destination: os.Path): os.Path = {
    val dest = destination / `class`.last
    os.copy(`class`, dest)
    dest
  }
}
