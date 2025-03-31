package mill.main.android.hilt

import mill.*
import mill.javalib.android.AndroidModule

trait AndroidHiltTransformAsmModule extends AndroidModule {
  def transformAsm(classes: Seq[PathRef]): T[Seq[PathRef]] = {
    val destination = Task.dest / "transform-asm"
    os.makeDir.all(destination)
    Task.traverse(classes) {
      pathRef =>
        Task.Anon {
          if (shouldTransform(pathRef.path))
            transform(pathRef.path, Task.dest / "transform-asm")
          else {
            val fileDest = destination / pathRef.path.last
            os.copy(pathRef.path, fileDest, createFolders = true)
            PathRef(fileDest)
          }
        }
    }()
  }


  private def shouldTransform(`class`: os.Path): Boolean = false

  private def transform(`class`: os.Path, destination: os.Path): PathRef = {
    val dest = destination / `class`.last
    os.copy(`class`, dest)
    PathRef(dest)
  }
}
