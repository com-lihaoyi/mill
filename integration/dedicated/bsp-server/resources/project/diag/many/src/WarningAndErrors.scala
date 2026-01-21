package diag

object WarningAndErrors {

  @deprecated("thing", "0.0.1")
  def printThing() = println(2)

  // 12 warnings for "printThing()" calls
  // 12 errors for not found value "foo"

  def thing0 = {
    printThing()
    foo
  }

  def thing1 = {
    printThing()
    foo
  }

  def thing2 = {
    printThing()
    foo
  }

  def thing3 = {
    printThing()
    foo
  }

  def thing4 = {
    printThing()
    foo
  }

  def thing5 = {
    printThing()
    foo
  }

  def thing6 = {
    printThing()
    foo
  }

  def thing7 = {
    printThing()
    foo
  }

  def thing8 = {
    printThing()
    foo
  }

  def thing9 = {
    printThing()
    foo
  }

  def thing10 = {
    printThing()
    foo
  }

  def thing11 = {
    printThing()
    foo
  }

}
