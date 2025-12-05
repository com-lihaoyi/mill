package mill.javalib.graalvm

case class MetadataQuery(
    rootPath: os.Path,
    deps: Set[String],
    useLatestConfigWhenVersionIsUntested: Boolean
)
