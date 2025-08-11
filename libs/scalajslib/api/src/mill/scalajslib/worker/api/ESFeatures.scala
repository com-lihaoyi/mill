package mill.scalajslib.worker.api

private[scalajslib] case class ESFeatures(
    allowBigIntsForLongs: Boolean,
    avoidClasses: Boolean,
    avoidLetsAndConsts: Boolean,
    esVersion: ESVersion
)
