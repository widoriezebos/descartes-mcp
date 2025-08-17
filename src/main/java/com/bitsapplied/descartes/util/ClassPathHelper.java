package com.bitsapplied.descartes.util;

import java.io.File;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utilities for mirroring classpath/module paths and related JShell helpers.
 */
public final class ClassPathHelper {

  private ClassPathHelper() {
    // no instances
  }

  /**
   * Builds an exact classpath string from the given classloader chain and module
   * paths.
   */
  public static String buildExactClassPath(ClassLoader cl) {
    Set<Path> entries = splitPathProp(System.getProperty("java.class.path"));

    for (URL url : collectUrlsFromClassLoaderChain(cl)) {
      Path p = urlToPathSilently(url);
      if (p != null) {
        entries.add(p);
      }
    }

    entries.addAll(splitPathProp(System.getProperty("jdk.module.path")));
    entries.addAll(splitPathProp(System.getProperty("jdk.module.upgrade.path")));
    entries.addAll(findModuleLocations(System.getProperty("jdk.module.path")));

    List<String> ordered = entries.stream().map(Path::toAbsolutePath).map(Path::normalize).map(Path::toString)
        .distinct().collect(Collectors.toList());

    return String.join(File.pathSeparator, ordered);
  }

  /**
   * Creates import statements for a class, its superclasses, and interfaces,
   * avoiding simple-name collisions.
   */
  public static List<String> buildImportsForInjected(Class<?> clazz) {
    Set<Class<?>> types = new LinkedHashSet<>();
    Deque<Class<?>> stack = new ArrayDeque<>();
    Class<?> root = baseType(clazz);
    if (root != null) {
      stack.push(root);
    }

    while (!stack.isEmpty()) {
      Class<?> c = stack.pop();
      if (c == null || !isImportable(c) || !types.add(c)) {
        continue;
      }

      Class<?> sc = baseType(c.getSuperclass());
      if (sc != null) {
        stack.push(sc);
      }
      for (Class<?> itf : c.getInterfaces()) {
        Class<?> bi = baseType(itf);
        if (bi != null) {
          stack.push(bi);
        }
      }
    }

    Map<String, String> bySimple = new LinkedHashMap<>();
    Set<String> collisions = new HashSet<>();
    for (Class<?> c : types) {
      String simple = c.getSimpleName();
      String fq = c.getName();
      if (bySimple.containsKey(simple) && !Objects.equals(bySimple.get(simple), fq)) {
        collisions.add(simple);
      } else {
        bySimple.put(simple, fq);
      }
    }

    return bySimple.entrySet().stream().filter(e -> !collisions.contains(e.getKey()))
        .map(e -> "import " + e.getValue() + ";").collect(Collectors.toList());
  }

  // ---------- internals ----------

  private static Set<Path> splitPathProp(String prop) {
    if (prop == null || prop.isBlank()) {
      return new LinkedHashSet<>();
    }
    String[] parts = prop.split(File.pathSeparator);
    LinkedHashSet<Path> set = new LinkedHashSet<>(Math.max(16, parts.length));
    for (String part : parts) {
      if (!part.isBlank()) {
        set.add(Paths.get(part));
      }
    }
    return set;
  }

  private static List<URL> collectUrlsFromClassLoaderChain(ClassLoader cl) {
    List<URL> urls = new ArrayList<>();
    for (ClassLoader cur = cl; cur != null; cur = cur.getParent()) {
      if (cur instanceof URLClassLoader ucl) {
        urls.addAll(Arrays.asList(ucl.getURLs()));
      } else {
        try {
          var ucpField = cur.getClass().getDeclaredField("ucp");
          ucpField.setAccessible(true);
          Object ucp = ucpField.get(cur);
          if (ucp != null) {
            var getURLs = ucp.getClass().getDeclaredMethod("getURLs");
            getURLs.setAccessible(true);
            URL[] more = (URL[]) getURLs.invoke(ucp);
            if (more != null) {
              urls.addAll(Arrays.asList(more));
            }
          }
        } catch (Throwable ignore) {
          // ignore
        }
      }
    }
    return urls;
  }

  private static Path urlToPathSilently(URL url) {
    try {
      return Paths.get(url.toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  private static Path uriToPathSilently(URI uri) {
    try {
      return Paths.get(uri);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Set<Path> findModuleLocations(String modulePathProp) {
    if (modulePathProp == null || modulePathProp.isBlank()) {
      return Collections.emptySet();
    }
    Set<Path> out = new LinkedHashSet<>();
    for (String root : modulePathProp.split(File.pathSeparator)) {
      if (root.isBlank()) {
        continue;
      }
      Path p = Paths.get(root);
      try {
        ModuleFinder.of(p).findAll().stream().map(ModuleReference::location).flatMap(Optional::stream)
            .map(ClassPathHelper::uriToPathSilently).filter(Objects::nonNull).forEach(out::add);
      } catch (Throwable ignore) {
        // ignore
      }
    }
    return out;
  }

  private static Class<?> baseType(Class<?> c) {
    if (c == null)
      return null;
    while (c.isArray())
      c = c.getComponentType();
    return c;
  }

  private static boolean isImportable(Class<?> c) {
    if (c == null || c.isPrimitive())
      return false;
    Package p = c.getPackage();
    if (p == null)
      return false;
    String pkg = p.getName();
    if ("java.lang".equals(pkg))
      return false;
    if (c.isAnonymousClass() || c.isLocalClass())
      return false;
    return c.getCanonicalName() != null;
  }
}
