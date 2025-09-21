package org.keycloak.theme;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;

public class ResourceLoader {

    public static InputStream getResourceAsStream(String root, String resource) throws IOException {
        if (root == null || resource == null) {
            return null;
        }
        String safeResource = sanitizeResourcePath(resource);
        if (safeResource == null) {
            return null;
        }
        // Path normalization
        Path rootPath = Path.of("/", root).normalize().toAbsolutePath();
        Path resourcePath = rootPath.resolve(safeResource).normalize().toAbsolutePath();
        if (!resourcePath.startsWith(rootPath)) {
            return null;
        }
        URL url = classLoader().getResource(safeResource);
        return url != null ? url.openStream() : null;
    }

    public static InputStream getFileAsStream(File root, String resource) throws IOException {
        File file = getFile(root, resource);
        return file != null && file.isFile() ? file.toURI().toURL().openStream() : null;
    }

    public static File getFile(File root, String resource) throws IOException {
        if (root == null || resource == null) {
            return null;
        }
        String safeResource = sanitizeResourcePath(resource);
        if (safeResource == null) {
            return null;
        }
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path resourcePath = rootPath.resolve(safeResource).normalize().toAbsolutePath();
        if (resourcePath.startsWith(rootPath)) {
            return resourcePath.toFile();
        } else {
            return null;
        }
    }
    /**
     * Sanitizes a resource path by rejecting any containing '%', replacing backslashes, and normalizing.
     * Returns null if invalid.
     */
    private static String sanitizeResourcePath(String resource) {
        if (resource.contains("%")) {
            return null;
        }
        // Replace backslashes with slashes
        resource = resource.replace('\\', '/');
        // Optionally, reject any '..' segments for extra safety
        if (resource.contains("..")) {
            return null;
        }
        return resource;
    }

    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

}
