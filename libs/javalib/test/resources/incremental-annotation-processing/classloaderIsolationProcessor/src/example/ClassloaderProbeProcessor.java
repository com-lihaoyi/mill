package example;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes("example.Probe")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ClassloaderProbeProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        StringBuilder sb = new StringBuilder();
        ClassLoader cur = getClass().getClassLoader();
        while (cur != null) {
            if (cur instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) cur).getURLs()) {
                    sb.append(url).append('\n');
                }
            }
            cur = cur.getParent();
        }

        try {
            FileObject file = processingEnv.getFiler().createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/probe/result.txt"
            );
            try (Writer writer = file.openWriter()) {
                writer.write(sb.toString());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return false;
    }
}
