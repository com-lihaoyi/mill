// == Backticked Names

import mill._
import mill.scalalib._

object `hyphenated-module` extends Module {
  def `hyphenated-target` = T{
    println("This is a hyphenated target in a hyphenated module.")
  }
}

object unhyphenatedModule extends Module {
  def unhyphenated_target = T{
    println("This is an unhyphenated target in an unhyphenated module.")
  }
}

// Mill modules and tasks may be composed of the following character types:
//
// * Alphanumeric (A-Z, a-z, and 0-9)
// * Underscore (`_`)
// * Hyphen (`-`)
//
// Due to Scala naming restrictions, module and task names with hyphens must be
// surrounded by back-ticks (```).
//
// Using hyphenated names at the command line is unaffected by these restrictions.

/* Example Usage

> ./mill hyphenated-module.hyphenated-target
This is a hyphenated target in a hyphenated module.

> ./mill unhyphenatedModule.unhyphenated_target
This is an unhyphenated target in an unhyphenated module.

*/