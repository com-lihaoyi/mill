//> using scala 3.6.1-RC1-bin-20241009-cd3bd1d-NIGHTLY

// NamedTuples part of the language now :)

val cls = (
  name = "JarManifest",
  values = List(),
  sum = false,
  hasApply = true,
  modifier = "private",
  ctor = List(
    "main" -> "Map[String, String]",
    "groups" -> "Map[String, Map[String, String]]",
    // "headless" -> "Boolean"
    // "dest" -> "mill.PathRef",
  )
)

val padding = 2

// derived values

val (ctorNames, ctorTypes) = (cls.ctor: List[(String, String)]).unzip

def asTuple[T](elems: List[T]): String =
  if elems.isEmpty then "EmptyTuple"
  else if elems.sizeIs == 1 then s"${elems.head} *: EmptyTuple"
  else elems.mkString("(", ", ", ")")

def factory(args: List[String]) =
  val list = args.mkString(",")
  if cls.hasApply then
    s"""${cls.name}.apply($list)"""
  else
    s"""new ${cls.name}($list)"""

val mod = if cls.modifier.isEmpty() then "" else s"${cls.modifier} "

val typedValues = cls.values: List[String]

val str = (
  if typedValues.nonEmpty then
    // singleton
    s"""// GENERATED CODE BY ${scriptPath} - DO NOT EDIT
private type SingletonMirrorProxy[T <: AnyRef & Singleton] = Mirror.SingletonProxy { val value: T }
private def genSingletonMirror[T <: AnyRef & Singleton](ref: T): SingletonMirrorProxy[T] =
  new Mirror.SingletonProxy(ref).asInstanceOf[SingletonMirrorProxy[T]]
${typedValues.map{ v =>
s"""${mod}given Mirror_${v.replace(".", "_")}: SingletonMirrorProxy[${v}.type] =
  genSingletonMirror(${v})"""}.mkString("\n")}"""
  else if cls.sum then
    // enum
    s"""// GENERATED CODE BY ${scriptPath} - DO NOT EDIT
${mod}given Mirror_${cls.name.replace(".", "_")}: Mirror.Sum with {
  final type MirroredMonoType = ${cls.name}
  final type MirroredType = ${cls.name}
  final type MirroredElemTypes = ${asTuple(ctorTypes)}
  final type MirroredElemLabels = ${asTuple(ctorNames.map(s => s"$"$s$""))}

  final def ordinal(p: ${cls.name}): Int = {
    p match {
      ${ctorTypes.zipWithIndex.map{ (tpe, i) =>
        s"case _: $tpe => $i"
      }.mkString("", "\n      ","")}
    }
  }
}"""
  else
    s"""// GENERATED CODE BY ${scriptPath} - DO NOT EDIT
${mod}given Mirror_${cls.name.replace(".", "_")}: Mirror.Product with {
  final type MirroredMonoType = ${cls.name}
  final type MirroredType = ${cls.name}
  final type MirroredElemTypes = ${asTuple(ctorTypes)}
  final type MirroredElemLabels = ${asTuple(ctorNames.map(s => s"$"$s$""))}

  final def fromProduct(p: scala.Product): ${cls.name} = {
    ${ctorTypes.zipWithIndex.map{ (tpe, i) =>
      s"val _${i + 1}: $tpe = p.productElement($i).asInstanceOf[$tpe]"
    }.mkString("", "\n    ","\n")}
    ${factory(ctorTypes.zipWithIndex.map{ (_, i) => s"_${i + 1}"})}
  }
}"""
)

println(str.linesIterator.map(s => s"${" " * padding}$s").mkString("\n"))
