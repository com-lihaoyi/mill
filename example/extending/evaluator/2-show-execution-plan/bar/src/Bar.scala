package bar

import foo.Foo

object Bar {
  def message = Foo.message + " and bar"
}
