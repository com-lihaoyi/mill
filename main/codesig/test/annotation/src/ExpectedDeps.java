package mill.codesig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface ExpectedDeps{
    String[] value() default {};
}
