package mill.main.gradle;

import java.io.Serializable;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
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

    public Pom pom() {
      return pom;
    }

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

      public String id() {
        return id;
      }

      public String name() {
        return name;
      }

      public String url() {
        return url;
      }

      public String org() {
        return org;
      }

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

      public String name() {
        return name;
      }

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

    String organization();

    String url();

    List<License> licenses();

    Scm scm();

    List<Dev> devs();

    String packaging();

    class Impl implements Pom {

      private final String description;
      private final String organization;
      private final String url;
      private final List<License> licenses;
      private final Scm scm;
      private final List<Dev> devs;
      private final String packaging;

      public Impl(
          String description,
          String organization,
          String url,
          List<License> licenses,
          Scm scm,
          List<Dev> devs,
          String packaging) {
        this.description = description;
        this.organization = organization;
        this.url = url;
        this.licenses = licenses;
        this.scm = scm;
        this.devs = devs;
        this.packaging = packaging;
      }

      public String description() {
        return description;
      }

      public String organization() {
        return organization;
      }

      public String url() {
        return url;
      }

      public List<License> licenses() {
        return licenses;
      }

      public Scm scm() {
        return scm;
      }

      public List<Dev> devs() {
        return devs;
      }

      public String packaging() {
        return packaging;
      }
    }

    static Pom from(DefaultMavenPom pom) {
      return new Impl(
          pom.getDescription().getOrNull(),
          pom.getOrganization() == null ? null : pom.getOrganization().getName().getOrNull(),
          pom.getUrl().getOrNull(),
          pom.getLicenses().stream().map(License::from).collect(Collectors.toList()),
          Scm.from(pom.getScm()),
          pom.getDevelopers().stream().map(Dev::from).collect(Collectors.toList()),
          pom.getPackaging());
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

      public String url() {
        return url;
      }

      public String connection() {
        return connection;
      }

      public String devConnection() {
        return devConnection;
      }

      public String tag() {
        return tag;
      }
    }

    static Scm from(MavenPomScm scm) {
      return null == scm
          ? null
          : new Impl(
              scm.getUrl().getOrNull(),
              scm.getConnection().getOrNull(),
              scm.getDeveloperConnection().getOrNull(),
              scm.getTag().getOrNull());
    }
  }
}
