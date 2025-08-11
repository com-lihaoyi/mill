package a;

// imports should be sorted
// unsed imports should be removed
import some.Configuration;
import some.GradleException;
import some.MavenPublication;
import some.Project;
import some.PublishingExtension;
import some.VariantVersionMappingStrategy;

public class Main {

    private static void configureResolvedVersionsWithVersionMapping(Project project) {
        project.getPluginManager().withPlugin("maven-publish", plugin -> {
            project.getExtensions()
                    .getByType(PublishingExtension.class)
                    .getPublications()
                    .withType(MavenPublication.class)
                    .configureEach(publication -> publication.versionMapping(mapping -> {
                        mapping.allVariants(VariantVersionMappingStrategy::fromResolutionResult);
                    }));
        });
    }

    private static GradleException notFound(String group, String name, Configuration configuration) {
        String actual = configuration.getIncoming().getResolutionResult().getAllComponents().stream()
                .map(ResolvedComponentResult::getModuleVersion)
                .map(mvi -> String.format("\t- %s:%s:%s", mvi.getGroup(), mvi.getName(), mvi.getVersion()))
                .collect(Collectors.joining("\n"));
        // ...
    }
}
