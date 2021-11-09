package mill.api

import scala.annotation.StaticAnnotation

/**
 * Annotation to mark internal API, which is not guaranteed to stay.
 */
class internal extends StaticAnnotation {}
