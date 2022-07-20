package mill.api

import scala.annotation.StaticAnnotation

/**
 * Annotation to mark experimental API, which is not guaranteed to stay.
 */
class experimental extends StaticAnnotation {}
