package mill.scalajslib.worker.api

private[scalajslib] final case class OutputPatterns(
    jsFile: String,
    sourceMapFile: String,
    moduleName: String,
    jsFileURI: String,
    sourceMapURI: String
)
