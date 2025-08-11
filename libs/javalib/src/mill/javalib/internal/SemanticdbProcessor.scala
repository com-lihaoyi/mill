// copied from https://github.com/VirtusLab/scala-cli/blob/26993dbd1e3da0714a7d704801642acb053ef518/modules/build/src/main/scala/scala/build/postprocessing/SemanticdbProcessor.scala
// that itself adapted it from https://github.com/com-lihaoyi/Ammonite/blob/2da846d2313f1e12e812802babf9c69005f5d44a/amm/interp/src/main/scala/ammonite/interp/script/SemanticdbProcessor.scala

package mill.javalib.internal

object SemanticdbProcessor {

  def postProcess(
      originalCode: String,
      originalPath: os.RelPath,
      adjust: Int => Option[Int],
      orig: os.Path
  ): Array[Byte] = ??? // bincompat stub

}
