package mill.androidlib

import mill.api.PathRef

case class AndroidModuleGeneratedDexVariants(
    androidDebugDex: PathRef,
    androidReleaseDex: PathRef,
    mainDexListOutput: PathRef
)

object AndroidModuleGeneratedDexVariants {
  implicit def resultRW: upickle.default.ReadWriter[AndroidModuleGeneratedDexVariants] =
    upickle.default.macroRW
}
