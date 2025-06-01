package mill.scalalib

import mill.define.ExternalModule

// A Spotless CLI is under development at https://github.com/diffplug/spotless-cli.
// Once a stable release is available, the worker implementation used here can be replaced.
package object spotless extends ExternalModule.Alias(SpotlessModule)
