package com.bitsapplied.descartes.profiler.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.profiler.parser.JFRParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Storage and retrieval of profile snapshots.
 *
 * <p>
 * Profiles are stored in memory and optionally persisted to disk as JSON files.
 */
public class ProfileStore {

  private static final Logger logger = LogManager.getLogger(ProfileStore.class);

  private final Map<String, ProfileSnapshot> profiles = new ConcurrentHashMap<>();
  private final Path storagePath;
  private final int maxStoredProfiles;
  private final ObjectMapper objectMapper;

  public ProfileStore(Path storagePath, int maxStoredProfiles) {
    this.storagePath = storagePath;
    this.maxStoredProfiles = maxStoredProfiles;
    this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // Ensure storage directory exists
    try {
      if (!Files.exists(storagePath)) {
        Files.createDirectories(storagePath);
        logger.info("Created profile storage directory: {}", storagePath);
      }
    } catch (IOException e) {
      logger.error("Failed to create profile storage directory: {}", storagePath, e);
    }
  }

  /**
   * Store a profile snapshot.
   *
   * @param snapshot Profile to store
   */
  public void store(ProfileSnapshot snapshot) {
    String profileId = snapshot.getMetadata().getProfileId();
    profiles.put(profileId, snapshot);
    logger.debug("Stored profile in memory: {}", profileId);

    // Persist to disk
    try {
      Path jsonFile = storagePath.resolve(profileId + ".json");
      objectMapper.writeValue(jsonFile.toFile(), snapshot.toMap());
      logger.info("Persisted profile to disk: {}", jsonFile);
    } catch (IOException e) {
      logger.error("Failed to persist profile: {}", profileId, e);
    }

    // Prune old profiles if over limit
    pruneOldProfiles();
  }

  /**
   * Get a profile by ID.
   *
   * @param profileId Profile ID
   * @return Profile snapshot, or null if not found
   */
  public ProfileSnapshot get(String profileId) {
    // Try memory first
    ProfileSnapshot snapshot = profiles.get(profileId);
    if (snapshot != null) {
      return snapshot;
    }

    // Try loading from disk
    return loadFromDisk(profileId);
  }

  /**
   * List all stored profile IDs, sorted by timestamp (most recent first).
   *
   * Scans disk for .json files to include profiles from previous sessions.
   *
   * @return List of profile IDs
   */
  public List<String> listProfileIds() {
    Set<String> ids = new HashSet<>();

    // Include profiles from memory (may not be persisted yet)
    ids.addAll(profiles.keySet());

    // Scan disk for .json files (may have older profiles not in memory)
    if (Files.exists(storagePath)) {
      try (Stream<Path> files = Files.list(storagePath)) {
        files.filter(p -> p.getFileName().toString().endsWith(".json"))
            .map(p -> p.getFileName().toString().replace(".json", ""))
            .forEach(ids::add);
      } catch (IOException e) {
        logger.error("Failed to list profile files from disk", e);
      }
    }

    // Sort by timestamp (newest first)
    return ids.stream()
        .sorted(Comparator.comparing(this::getProfileTimestamp).reversed())
        .collect(Collectors.toList());
  }

  /**
   * Get profile timestamp for sorting (file modification time, or profile start time if in memory only).
   */
  private long getProfileTimestamp(String profileId) {
    // First try in-memory profile (for newly created profiles not yet persisted)
    ProfileSnapshot snapshot = profiles.get(profileId);
    if (snapshot != null) {
      return snapshot.getMetadata().getStartTime().toEpochMilli();
    }

    // Fall back to file modification time for disk-only profiles
    try {
      Path jsonFile = storagePath.resolve(profileId + ".json");
      if (Files.exists(jsonFile)) {
        return Files.getLastModifiedTime(jsonFile).toMillis();
      }
    } catch (IOException e) {
      logger.warn("Failed to get timestamp for profile: {}", profileId);
    }
    return 0;
  }

  /**
   * List all stored profile metadata.
   *
   * @return List of profiles
   */
  public List<ProfileSnapshot> listProfiles() {
    return new ArrayList<>(profiles.values());
  }

  /**
   * Delete a profile.
   *
   * @param profileId Profile ID to delete
   * @return true if deleted, false if not found
   */
  public boolean delete(String profileId) {
    ProfileSnapshot removed = profiles.remove(profileId);

    // Delete from disk
    Path jsonFile = storagePath.resolve(profileId + ".json");
    Path jfrFile = storagePath.resolve(profileId + ".jfr");

    try {
      boolean deletedJson = Files.deleteIfExists(jsonFile);
      boolean deletedJfr = Files.deleteIfExists(jfrFile);
      if (deletedJson || deletedJfr) {
        logger.info("Deleted profile from disk: {}", profileId);
      }
    } catch (IOException e) {
      logger.error("Failed to delete profile files: {}", profileId, e);
    }

    return removed != null;
  }

  /**
   * Delete all profiles.
   */
  public void deleteAll() {
    for (String profileId : new ArrayList<>(profiles.keySet())) {
      delete(profileId);
    }
    logger.info("Deleted all profiles");
  }

  /**
   * Get the storage path.
   */
  public Path getStoragePath() {
    return storagePath;
  }

  /**
   * Validates that a profile ID is safe and doesn't contain path traversal
   * attempts.
   *
   * @param profileId the profile ID to validate
   * @throws IllegalArgumentException if the profile ID is invalid or contains
   *                                  path traversal
   */
  private void validateProfileId(String profileId) {
    if (profileId == null || profileId.isBlank()) {
      throw new IllegalArgumentException("Profile ID cannot be null or empty");
    }

    // Reject path traversal attempts
    if (profileId.contains("..") || profileId.contains("/") || profileId.contains("\\")) {
      throw new IllegalArgumentException("Invalid profile ID: contains path traversal characters");
    }

    // Ensure filename-safe characters only (alphanumeric, dash, underscore, dot)
    if (!profileId.matches("^[a-zA-Z0-9._-]+$")) {
      throw new IllegalArgumentException(
          "Invalid profile ID: must contain only alphanumeric, dash, underscore, dot characters");
    }
  }

  /**
   * Get the JFR file path for a profile.
   */
  public Path getJFRPath(String profileId) {
    validateProfileId(profileId);
    Path resolvedPath = storagePath.resolve(profileId + ".jfr");

    // Additional safety check: ensure resolved path is within storage directory
    try {
      if (!resolvedPath.normalize().startsWith(storagePath.normalize())) {
        throw new IllegalArgumentException("Profile path escapes storage directory");
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid profile path: " + e.getMessage(), e);
    }

    return resolvedPath;
  }

  /**
   * Get the JSON file path for a profile.
   */
  public Path getJSONPath(String profileId) {
    validateProfileId(profileId);
    Path resolvedPath = storagePath.resolve(profileId + ".json");

    // Additional safety check: ensure resolved path is within storage directory
    try {
      if (!resolvedPath.normalize().startsWith(storagePath.normalize())) {
        throw new IllegalArgumentException("Profile path escapes storage directory");
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid profile path: " + e.getMessage(), e);
    }

    return resolvedPath;
  }

  private ProfileSnapshot loadFromDisk(String profileId) {
    // Use validated path method to prevent path traversal
    Path jfrFile = getJFRPath(profileId);
    if (!Files.exists(jfrFile)) {
      logger.debug("JFR file not found for profile: {}", profileId);
      return null;
    }

    try {
      // Load config from JSON if available, otherwise use defaults
      ProfilerConfig config = loadConfigFromJson(profileId);

      // Re-parse the JFR file
      JFRParser parser = new JFRParser(config.getPackageFilter());
      ProfileSnapshot snapshot = parser.parse(jfrFile, profileId, config);

      // Cache in memory for next access
      profiles.put(profileId, snapshot);

      logger.info("Loaded profile from disk: {}", profileId);
      return snapshot;

    } catch (Exception e) {
      logger.error("Failed to load profile from disk: {}", profileId, e);
      return null;
    }
  }

  /**
   * Load ProfilerConfig from JSON metadata file.
   */
  private ProfilerConfig loadConfigFromJson(String profileId) {
    Path jsonFile = storagePath.resolve(profileId + ".json");

    if (Files.exists(jsonFile)) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(jsonFile.toFile(), Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
        if (metadata != null) {
          @SuppressWarnings("unchecked")
          Map<String, Object> configMap = (Map<String, Object>) metadata.get("configuration");
          Integer samplingInterval = (Integer) metadata.get("sampling_interval_ms");

          if (configMap != null) {
            return ProfilerConfig.builder().samplingInterval(samplingInterval != null ? samplingInterval : 10)
                .packageFilter((String) configMap.getOrDefault("package_filter", ""))
                .cpuProfilingEnabled((Boolean) configMap.getOrDefault("cpu_profiling", true))
                .allocationProfilingEnabled((Boolean) configMap.getOrDefault("allocation_profiling", false))
                .lockProfilingEnabled((Boolean) configMap.getOrDefault("lock_profiling", false))
                .ioProfilingEnabled((Boolean) configMap.getOrDefault("io_profiling", false))
                .gcProfilingEnabled((Boolean) configMap.getOrDefault("gc_profiling", false)).build();
          }
        }
      } catch (Exception e) {
        logger.warn("Failed to load config from JSON for profile: {}, using defaults", profileId);
      }
    }

    // Return default config if JSON not available
    return ProfilerConfig.builder().cpuOnly().samplingInterval(10).packageFilter("").build();
  }

  private void pruneOldProfiles() {
    if (profiles.size() <= maxStoredProfiles) {
      return;
    }

    // Sort by timestamp (oldest first) and remove oldest
    List<ProfileSnapshot> sorted = profiles.values().stream()
        .sorted(Comparator.comparing(s -> s.getMetadata().getStartTime())).collect(Collectors.toList());

    int toRemove = profiles.size() - maxStoredProfiles;
    for (int i = 0; i < toRemove; i++) {
      String profileId = sorted.get(i).getMetadata().getProfileId();
      delete(profileId);
      logger.info("Pruned old profile: {}", profileId);
    }
  }
}
