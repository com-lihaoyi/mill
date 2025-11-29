package mill.api.internal

case class LocatedValue(path: os.Path, index: Int, value: upickle.core.BufferedValue)
