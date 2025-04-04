package mill.runner.api

/**
 * A somewhat-type-safe wrapper around `Any`. Stores an un-typed value, but
 * can only be created explicitly by wrapping in `Val(_)` and de-constructed
 * explicitly via `.value`. That makes it much less likely to introduce bugs
 * passing the wrong thing, e.g. `(Any, Int)` can be passed to `Any`, but
 * `(Val, Int)` cannot be passed to `Val`
 */
case class Val(value: Any)
