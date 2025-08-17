package com.bitsapplied.descartes.resources;

import java.io.File;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Resource that provides information about the application's classpath,
 * loaded classes, and class loaders.
 */
public class ClasspathResource implements MCPResourceHandler {
  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String getUriPath() {
    return "classpath";
  }

  @Override
  public String getName() {
    return "Classpath Information";
  }

  @Override
  public String getDescription() {
    return "JVM classpath and class loading analyzer for dependency management and troubleshooting. "
        + "Lists all JAR files and directories on classpath, tracks loaded vs total class count, "
        + "examines class loader hierarchy, and helps identify classpath conflicts or missing dependencies. "
        + "Parameters: 'action' (summary/jars/classes/loaders), 'filter' (pattern for class names). "
        + "Useful for debugging NoClassDefFoundError, version conflicts, and understanding runtime dependencies.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String action = queryParams.get("action", "summary");

      switch (action) {
      case "summary":
        return getClasspathSummary();
      case "jars":
        return getLoadedJars();
      case "packages":
        String prefix = queryParams.get("prefix", "");
        return getPackages(prefix);
      case "classloaders":
        return getClassLoaders();
      case "search":
        String pattern = queryParams.get("pattern", "");
        return searchClasses(pattern);
      default:
        throw new MCPResource.ResourceException("Unknown action: " + action);
      }
    } catch (MCPResource.ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new MCPResource.ResourceException("Error handling classpath request", e);
    }
  }

  private String getClasspathSummary() throws Exception {
    ObjectNode result = mapper.createObjectNode();

    // Class loading statistics
    ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();
    ObjectNode stats = result.putObject("statistics");
    stats.put("loadedClassCount", classLoadingBean.getLoadedClassCount());
    stats.put("totalLoadedClassCount", classLoadingBean.getTotalLoadedClassCount());
    stats.put("unloadedClassCount", classLoadingBean.getUnloadedClassCount());

    // Classpath entries
    String classpath = System.getProperty("java.class.path");
    ArrayNode classpathArray = result.putArray("classpath");
    if (classpath != null) {
      for (String path : classpath.split(File.pathSeparator)) {
        classpathArray.add(path);
      }
    }

    // Boot classpath (if available)
    String bootClasspath = System.getProperty("sun.boot.class.path");
    if (bootClasspath != null) {
      ArrayNode bootArray = result.putArray("bootClasspath");
      for (String path : bootClasspath.split(File.pathSeparator)) {
        bootArray.add(path);
      }
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getLoadedJars() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode jarsArray = result.putArray("jars");

    Set<String> processedUrls = new HashSet<>();

    // Get URLs from all class loaders
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    while (cl != null) {
      if (cl instanceof URLClassLoader) {
        @SuppressWarnings("resource")
        URLClassLoader urlCl = (URLClassLoader) cl;
        for (URL url : urlCl.getURLs()) {
          String urlStr = url.toString();
          if (!processedUrls.contains(urlStr)) {
            processedUrls.add(urlStr);
            ObjectNode jarNode = jarsArray.addObject();
            jarNode.put("url", urlStr);
            jarNode.put("protocol", url.getProtocol());
            jarNode.put("file", url.getFile());

            // Extract jar name if it's a jar file
            if (urlStr.endsWith(".jar")) {
              String fileName = url.getFile();
              int lastSlash = fileName.lastIndexOf('/');
              if (lastSlash >= 0) {
                jarNode.put("name", fileName.substring(lastSlash + 1));
              }
            }
          }
        }
      }
      cl = cl.getParent();
    }

    result.put("count", processedUrls.size());
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getPackages(String prefix) throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode packagesArray = result.putArray("packages");

    // Get all packages
    Package[] packages = Package.getPackages();
    Set<String> packageNames = new TreeSet<>();

    for (Package pkg : packages) {
      String name = pkg.getName();
      if (prefix.isEmpty() || name.startsWith(prefix)) {
        packageNames.add(name);
      }
    }

    // Group by top-level package
    Map<String, List<String>> grouped = packageNames.stream().collect(Collectors.groupingBy(name -> {
      int dot = name.indexOf('.');
      return dot > 0 ? name.substring(0, dot) : name;
    }));

    for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
      ObjectNode groupNode = packagesArray.addObject();
      groupNode.put("root", entry.getKey());
      ArrayNode subPackages = groupNode.putArray("packages");
      for (String pkg : entry.getValue()) {
        subPackages.add(pkg);
      }
      groupNode.put("count", entry.getValue().size());
    }

    result.put("totalCount", packageNames.size());
    result.put("groupCount", grouped.size());
    if (!prefix.isEmpty()) {
      result.put("prefix", prefix);
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getClassLoaders() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode loadersArray = result.putArray("classLoaders");

    Set<ClassLoader> processed = new HashSet<>();
    ClassLoader cl = Thread.currentThread().getContextClassLoader();

    while (cl != null && !processed.contains(cl)) {
      processed.add(cl);
      ObjectNode loaderNode = loadersArray.addObject();
      loaderNode.put("className", cl.getClass().getName());
      loaderNode.put("hashCode", cl.hashCode());
      loaderNode.put("toString", cl.toString());

      if (cl instanceof URLClassLoader) {
        @SuppressWarnings("resource")
        URLClassLoader urlCl = (URLClassLoader) cl;
        ArrayNode urlsArray = loaderNode.putArray("urls");
        for (URL url : urlCl.getURLs()) {
          urlsArray.add(url.toString());
        }
      }

      // Add parent reference
      if (cl.getParent() != null) {
        loaderNode.put("parentHashCode", cl.getParent().hashCode());
        loaderNode.put("parentClass", cl.getParent().getClass().getName());
      } else {
        loaderNode.put("parent", "Bootstrap ClassLoader");
      }

      cl = cl.getParent();
    }

    result.put("count", processed.size());
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String searchClasses(String pattern) throws Exception {
    if (pattern == null || pattern.isEmpty()) {
      throw new MCPResource.ResourceException("Pattern parameter is required for search");
    }

    ObjectNode result = mapper.createObjectNode();
    ArrayNode classesArray = result.putArray("classes");

    // This is a simplified search that looks through package names
    // A more comprehensive implementation would scan JAR files
    Package[] packages = Package.getPackages();
    Set<String> matchingPackages = new TreeSet<>();

    String lowerPattern = pattern.toLowerCase();
    for (Package pkg : packages) {
      String name = pkg.getName();
      if (name.toLowerCase().contains(lowerPattern)) {
        matchingPackages.add(name);
      }
    }

    for (String pkgName : matchingPackages) {
      ObjectNode classNode = classesArray.addObject();
      classNode.put("package", pkgName);
      classNode.put("type", "package");
    }

    result.put("pattern", pattern);
    result.put("matchCount", matchingPackages.size());
    result.put("note", "This search currently only matches package names. Full class search requires JAR scanning.");

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }
}