package mill.scalajslib.api

import upickle.ReadWriter

case class ESFeatures private (
    allowBigIntsForLongs: Boolean,
    avoidClasses: Boolean,
    avoidLetsAndConsts: Boolean,
    esVersion: ESVersion
) derives ReadWriter {

  def withAllowBigIntsForLongs(allowBigIntsForLongs: Boolean): ESFeatures =
    copy(allowBigIntsForLongs = allowBigIntsForLongs)

  def withAvoidClasses(avoidClasses: Boolean): ESFeatures =
    copy(avoidClasses = avoidClasses)

  def withAvoidLetsAndConsts(avoidLetsAndConsts: Boolean): ESFeatures =
    copy(avoidLetsAndConsts = avoidLetsAndConsts)

  def withESVersion(esVersion: ESVersion): ESFeatures =
    copy(esVersion = esVersion)
}

object ESFeatures {
  val Defaults: ESFeatures = ESFeatures(
    allowBigIntsForLongs = false,
    avoidClasses = true,
    avoidLetsAndConsts = true,
    esVersion = ESVersion.ES2015
  )
}
