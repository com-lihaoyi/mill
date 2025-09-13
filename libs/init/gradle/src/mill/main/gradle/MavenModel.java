package mill.main.gradle;

import java.io.Serializable;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.publish.maven.MavenPomDeveloper;
import org.gradle.api.publish.maven.MavenPomLicense;
import org.gradle.api.publish.maven.MavenPomScm;
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;

/**
 * A model containing the <a href="https://docs.gradle.org/current/userguide/publishing_maven.html">Gradle Maven Plugin<a/> settings for a project.
 */
public interface MavenModel extends Serializable {

  Pom pom();

  Set<URI> repositories();

  class Impl implements MavenModel {

    private final Pom pom;
    private final Set<URI> reps;

    public Impl(Pom pom, Set<URI> reps) {
      this.pom = pom;
      this.reps = reps;
    }

    @Override
    public Pom pom() {
      return pom;
    }

    @Override
    public Set<URI> repositories() {
      return reps;
    }
  }

  static MavenModel from(Project project) {
    Pom pom = project.getTasks().withType(GenerateMavenPom.class).stream()
        .map(GenerateMavenPom::getPom)
        .flatMap(p -> (p instanceof DefaultMavenPom)
            ? Stream.of(Pom.from((DefaultMavenPom) p))
            : Stream.empty())
        .findFirst()
        .orElse(null);
    RepositoryHandler rep = project.getRepositories();
    List<URI> skipReps = new LinkedList<>();
    skipReps.add(rep.mavenCentral().getUrl());
    skipReps.add(rep.mavenLocal().getUrl());
    if (rep.gradlePluginPortal() instanceof MavenArtifactRepository) {
      skipReps.add(((MavenArtifactRepository) rep.gradlePluginPortal()).getUrl());
    }

    Set<URI> reps = rep.stream()
        .flatMap(repo -> repo instanceof MavenArtifactRepository
            ? Stream.of(((MavenArtifactRepository) repo).getUrl())
            : Stream.empty())
        .filter(uri -> !skipReps.contains(uri))
        .collect(Collectors.toSet());
    return new Impl(pom, reps);
  }

  interface Dev extends Serializable {

    String id();

    String name();

    String url();

    String org();

    String orgUrl();

    class Impl implements Dev {

      private final String id;
      private final String name;
      private final String url;
      private final String org;
      private final String orgUrl;

      public Impl(String id, String name, String url, String org, String orgUrl) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.org = org;
        this.orgUrl = orgUrl;
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String url() {
        return url;
      }

      @Override
      public String org() {
        return org;
      }

      @Override
      public String orgUrl() {
        return orgUrl;
      }
    }

    static Dev from(MavenPomDeveloper dev) {
      return new Impl(
          dev.getId().getOrNull(),
          dev.getName().getOrNull(),
          dev.getUrl().getOrNull(),
          dev.getOrganization().getOrNull(),
          dev.getOrganizationUrl().getOrNull());
    }
  }

  interface License extends Serializable {

    String name();

    String url();

    class Impl implements License {
      private final String name;
      private final String url;

      public Impl(String name, String url) {
        this.name = name;
        this.url = url;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public String url() {
        return url;
      }
    }

    static License from(MavenPomLicense license) {
      return new Impl(license.getName().getOrNull(), license.getUrl().getOrNull());
    }
  }

  interface Pom extends Serializable {

    String description();

    String url();

    List<License> licenses();

    Scm scm();

    List<Dev> devs();

    String packaging();

    List<Prop> properties();

    class Impl implements Pom {

      private final String description;
      private final String url;
      private final List<License> licenses;
      private final Scm scm;
      private final List<Dev> devs;
      private final String packaging;
      private final List<Prop> properties;

      public Impl(
          String description,
          String url,
          List<License> licenses,
          Scm scm,
          List<Dev> devs,
          String packaging,
          List<Prop> properties) {
        this.description = description;
        this.url = url;
        this.licenses = licenses;
        this.scm = scm;
        this.devs = devs;
        this.packaging = packaging;
        this.properties = properties;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String url() {
        return url;
      }

      @Override
      public List<License> licenses() {
        return licenses;
      }

      @Override
      public Scm scm() {
        return scm;
      }

      @Override
      public List<Dev> devs() {
        return devs;
      }

      @Override
      public String packaging() {
        return packaging;
      }

      @Override
      public List<Prop> properties() {
        return properties;
      }
    }

    static Pom from(DefaultMavenPom pom) {
      List<Prop> properties = Stream.ofNullable(pom.getProperties().getOrNull())
          .flatMap(map -> map.entrySet().stream())
          .map(Prop::from)
          .collect(Collectors.toList());
      return new Impl(
          pom.getDescription().getOrNull(),
          pom.getUrl().getOrNull(),
          pom.getLicenses().stream().map(License::from).collect(Collectors.toList()),
          Scm.from(pom.getScm()),
          pom.getDevelopers().stream().map(Dev::from).collect(Collectors.toList()),
          pom.getPackaging(),
          properties);
    }
  }

  interface Prop extends Serializable {

    String key();

    String value();

    class Impl implements Prop {
      private final String key;
      private final String value;

      public Impl(String key, String value) {
        this.key = key;
        this.value = value;
      }

      @Override
      public String key() {
        return key;
      }

      @Override
      public String value() {
        return value;
      }
    }

    static Prop from(Map.Entry<String, String> entry) {
      return new Impl(entry.getKey(), entry.getValue());
    }
  }

  interface Scm extends Serializable {

    String url();

    String connection();

    String devConnection();

    String tag();

    class Impl implements Scm {
      private final String url;
      private final String connection;
      private final String devConnection;
      private final String tag;

      public Impl(String url, String connection, String devConnection, String tag) {
        this.url = url;
        this.connection = connection;
        this.devConnection = devConnection;
        this.tag = tag;
      }

      @Override
      public String url() {
        return url;
      }

      @Override
      public String connection() {
        return connection;
      }

      @Override
      public String devConnection() {
        return devConnection;
      }

      @Override
      public String tag() {
        return tag;
      }
    }

    static Scm from(MavenPomScm scm) {
      return scm == null
          ? null
          : new Impl(
              scm.getUrl().getOrNull(),
              scm.getConnection().getOrNull(),
              scm.getDeveloperConnection().getOrNull(),
              scm.getTag().getOrNull());
    }
  }
}
