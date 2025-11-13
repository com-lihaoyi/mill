package mill.runner.autooverride

import scala.quoted.*

/**
 * A trait that enables automatic implementation of abstract methods.
 *
 * Any concrete object that extends AutoOverride[T] will have any unimplemented
 * abstract methods with return type <: T automatically implemented by calling
 * this.autoOverrideImpl[T]().
 *
 * The implementing class must provide an inline def autoOverrideImpl[T] macro
 * that resolves LiteralImplicit[T] during macro expansion.
 *
 * @tparam T the return type that will be automatically implemented
 */
trait AutoOverride[T]
