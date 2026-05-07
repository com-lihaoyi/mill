package example;

import java.io.IOException;
import java.io.Writer;
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

        ClassLoader cl = getClass().getClassLoader();
        ClassLoader parent = cl.getParent();
        StringBuilder sb = new StringBuilder();
        sb.append("self=").append(cl.getClass().getName()).append('\n');
        sb.append("parent=").append(parent == null ? "<bootstrap>" : parent.getClass().getName())
            .append('\n');
        sb.append("parent-is-platform=")
            .append(parent == ClassLoader.getPlatformClassLoader())
            .append('\n');

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
