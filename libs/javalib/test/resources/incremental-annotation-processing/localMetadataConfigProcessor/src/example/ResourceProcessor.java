package example;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes("example.Generate")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ResourceProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        for (Element element : roundEnv.getElementsAnnotatedWith(Generate.class)) {
            try {
                FileObject file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/incremental/" + ((TypeElement) element).getQualifiedName() + ".txt",
                    element
                );
                try (Writer writer = file.openWriter()) {
                    writer.write(element.getSimpleName().toString());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return false;
    }
}
