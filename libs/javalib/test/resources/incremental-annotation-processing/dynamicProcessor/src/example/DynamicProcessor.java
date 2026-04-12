package example;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes("example.GenerateDynamic")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class DynamicProcessor extends AbstractProcessor {
    @Override
    public Set<String> getSupportedOptions() {
        HashSet<String> options = new HashSet<>();
        options.add("org.gradle.annotation.processing.aggregating");
        return options;
    }

    @Override
    public boolean process(Set<? extends javax.lang.model.element.TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(GenerateDynamic.class);
        if (elements.isEmpty()) {
            return false;
        }

        try {
            Filer filer = processingEnv.getFiler();
            FileObject output = filer.createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/dynamic/all.txt",
                elements.toArray(new Element[0])
            );
            try (Writer writer = output.openWriter()) {
                List<String> names = new ArrayList<>();
                for (Element element : elements) {
                    names.add(element.toString());
                }
                Collections.sort(names);
                writer.write(String.join("\n", names));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return false;
    }
}
