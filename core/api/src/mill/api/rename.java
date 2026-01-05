package mill.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to rename a task or command on the command line.
 * This is useful when the method name in code conflicts with a reserved word
 * or package name, but you still want to expose a convenient name on the CLI.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface rename {
    /**
     * The new name to use on the command line (replaces the method name)
     */
    String value();
}
