package success.pkg.innercycle

package object a {
  val p: A1 with A2 = null
  import acyclic.pkg
}
