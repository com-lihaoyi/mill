package mill.androidlib

import mill.define.PathRef

case class AndroidModuleGeneratedDexVariants(
    androidDebugDex: PathRef,
    androidReleaseDex: PathRef,
    mainDexListOutput: PathRef
)

object AndroidModuleGeneratedDexVariants {
  implicit def resultRW: upickle.default.ReadWriter[AndroidModuleGeneratedDexVariants] =
    upickle.default.macroRW
}
