package mill.scalalib

import mill.define.ExternalModule

// A Spotless CLI is under development at https://github.com/diffplug/spotless-cli.
// Once a stable release is available, this implementation can be replaced.
package object spotless extends ExternalModule.Alias(SpotlessModule)
