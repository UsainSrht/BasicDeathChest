package me.usainsrht.basicdeathchest;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

public class PluginLoaderImpl implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        // Add standard Maven Central repository
        resolver.addRepository(new RemoteRepository.Builder(
                "central",
                "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        // Add SQLite JDBC dependency to be resolved and loaded at runtime
        resolver.addDependency(new Dependency(
                new DefaultArtifact("org.xerial:sqlite-jdbc:3.45.3.0"),
                null));

        classpathBuilder.addLibrary(resolver);
    }
}
