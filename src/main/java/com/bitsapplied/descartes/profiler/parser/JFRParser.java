package com.bitsapplied.descartes.profiler.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.profiler.ProfilerException;
import com.bitsapplied.descartes.profiler.config.ProfilerConfig;
import com.bitsapplied.descartes.profiler.model.Hotspot;
import com.bitsapplied.descartes.profiler.model.ProfileMetadata;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Parses JFR binary files and converts them into ProfileSnapshot objects.
 *
 * <p>
 * Processes JFR events to extract CPU hotspots, allocation sites, lock
 * contentions, and call trees.
 */
public class JFRParser {

  private static final Logger logger = LogManager.getLogger(JFRParser.class);

  private final String packageFilter;

  public JFRParser(String packageFilter) {
    this.packageFilter = packageFilter;
  }

  /**
   * Parse a JFR file and create a ProfileSnapshot.
   *
   * @param jfrFile   Path to .jfr file
   * @param profileId Profile ID
   * @param config    Original profiler configuration
   * @return Parsed profile snapshot
   */
  public ProfileSnapshot parse(Path jfrFile, String profileId, ProfilerConfig config) {
    logger.info("Parsing JFR file: {} (size={}KB)", jfrFile, jfrFile.toFile().length() / 1024);

    Map<String, Long> cpuSamples = new HashMap<>();
    Map<String, Long> allocationBytes = new HashMap<>();
    Map<String, Long> lockDurations = new HashMap<>();
    CallTreeBuilder callTreeBuilder = new CallTreeBuilder(packageFilter);

    long totalCPUSamples = 0;
    long totalAllocationEvents = 0;
    long totalLockEvents = 0;
    Instant startTime = null;
    Instant endTime = null;

    try (RecordingFile recording = new RecordingFile(jfrFile)) {
      while (recording.hasMoreEvents()) {
        RecordedEvent event = recording.readEvent();
        String eventType = event.getEventType().getName();

        // Track time range
        if (startTime == null) {
          startTime = event.getStartTime();
        }
        endTime = event.getEndTime();

        switch (eventType) {
        case "jdk.ExecutionSample":
          processCPUSample(event, cpuSamples, callTreeBuilder);
          totalCPUSamples++;
          break;

        case "jdk.ObjectAllocationInNewTLAB":
        case "jdk.ObjectAllocationOutsideTLAB":
          processAllocation(event, allocationBytes);
          totalAllocationEvents++;
          break;

        case "jdk.JavaMonitorEnter":
        case "jdk.JavaMonitorWait":
        case "jdk.ThreadPark":
          processLockContention(event, lockDurations);
          totalLockEvents++;
          break;

        default:
          // Ignore other events
          break;
        }
      }
    } catch (IOException e) {
      throw new ProfilerException("Failed to parse JFR file: " + jfrFile, e);
    }

    logger.info("Parsed {} CPU samples, {} allocation events, {} lock events", totalCPUSamples, totalAllocationEvents,
        totalLockEvents);

    // Handle case where JFR file has no events (empty recording)
    if (startTime == null) {
      // Use file timestamps as fallback
      try {
        startTime = Files.getLastModifiedTime(jfrFile).toInstant().minus(config.getDuration());
        endTime = Files.getLastModifiedTime(jfrFile).toInstant();
      } catch (IOException e) {
        // Last resort: use current time
        endTime = Instant.now();
        startTime = endTime.minus(config.getDuration());
      }
    }

    // Build metadata
    ProfileMetadata metadata = ProfileMetadata.builder().profileId(profileId).startTime(startTime).endTime(endTime)
        .config(config).recordingSource("JFR").build();

    // Build hotspots
    List<Hotspot> cpuHotspots = buildHotspots(cpuSamples, totalCPUSamples, Hotspot.HotspotType.CPU);
    List<Hotspot> allocationHotspots = buildHotspots(allocationBytes,
        allocationBytes.values().stream().mapToLong(Long::longValue).sum(), Hotspot.HotspotType.ALLOCATION);
    List<Hotspot> lockHotspots = buildHotspots(lockDurations,
        lockDurations.values().stream().mapToLong(Long::longValue).sum(), Hotspot.HotspotType.LOCK);

    // Build snapshot
    ProfileSnapshot.Builder snapshotBuilder = ProfileSnapshot.builder().metadata(metadata).totalSamples(totalCPUSamples)
        .cpuHotspots(cpuHotspots).allocationHotspots(allocationHotspots).lockHotspots(lockHotspots)
        .callTrees(callTreeBuilder.getRootNodes());

    // Generate insights
    generateInsights(snapshotBuilder, cpuHotspots, allocationHotspots, lockHotspots);

    return snapshotBuilder.build();
  }

  private void processCPUSample(RecordedEvent event, Map<String, Long> cpuSamples, CallTreeBuilder callTreeBuilder) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
      return;
    }

    // Add to call tree
    callTreeBuilder.addSample(stackTrace);

    // Extract top frame for hotspot
    RecordedFrame topFrame = stackTrace.getFrames().get(0);
    RecordedMethod method = topFrame.getMethod();
    if (method == null) {
      return;
    }

    String methodKey = formatMethodKey(method, topFrame.getLineNumber());

    // Apply package filter
    if (packageFilter != null && !packageFilter.isEmpty()) {
      if (!method.getType().getName().startsWith(packageFilter)) {
        return;
      }
    }

    cpuSamples.merge(methodKey, 1L, Long::sum);
  }

  private void processAllocation(RecordedEvent event, Map<String, Long> allocationBytes) {
    long allocationSize = event.getLong("allocationSize");

    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
      return;
    }

    RecordedFrame topFrame = stackTrace.getFrames().get(0);
    RecordedMethod method = topFrame.getMethod();
    if (method == null) {
      return;
    }

    String methodKey = formatMethodKey(method, topFrame.getLineNumber());

    // Apply package filter
    if (packageFilter != null && !packageFilter.isEmpty()) {
      if (!method.getType().getName().startsWith(packageFilter)) {
        return;
      }
    }

    allocationBytes.merge(methodKey, allocationSize, Long::sum);
  }

  private void processLockContention(RecordedEvent event, Map<String, Long> lockDurations) {
    long duration = event.getDuration().toMillis();

    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
      return;
    }

    RecordedFrame topFrame = stackTrace.getFrames().get(0);
    RecordedMethod method = topFrame.getMethod();
    if (method == null) {
      return;
    }

    String methodKey = formatMethodKey(method, topFrame.getLineNumber());

    // Apply package filter
    if (packageFilter != null && !packageFilter.isEmpty()) {
      if (!method.getType().getName().startsWith(packageFilter)) {
        return;
      }
    }

    lockDurations.merge(methodKey, duration, Long::sum);
  }

  private String formatMethodKey(RecordedMethod method, int lineNumber) {
    String className = method.getType().getName();
    String methodName = method.getName();
    return className + "." + methodName + ":" + lineNumber;
  }

  private List<Hotspot> buildHotspots(Map<String, Long> samples, long totalSamples, Hotspot.HotspotType type) {
    List<Hotspot> hotspots = new ArrayList<>();

    int rank = 1;
    for (Map.Entry<String, Long> entry : samples.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).toList()) {

      String methodKey = entry.getKey();
      long sampleCount = entry.getValue();
      double percentage = totalSamples > 0 ? (sampleCount * 100.0) / totalSamples : 0.0;

      // Parse method key: "com.foo.Bar.method:123"
      String[] parts = methodKey.split(":");
      String fullMethod = parts[0];
      int lineNumber = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;

      int lastDot = fullMethod.lastIndexOf('.');
      String className = fullMethod.substring(0, lastDot);
      String methodName = fullMethod.substring(lastDot + 1);
      String sourceFile = className.substring(className.lastIndexOf('.') + 1) + ".java";

      Hotspot hotspot = Hotspot.builder().rank(rank++).className(className).methodName(methodName)
          .sourceFile(sourceFile).lineNumber(lineNumber).percentage(percentage).sampleCount(sampleCount).type(type)
          .build();

      hotspots.add(hotspot);
    }

    return hotspots;
  }

  private void generateInsights(ProfileSnapshot.Builder builder, List<Hotspot> cpuHotspots,
      List<Hotspot> allocationHotspots, List<Hotspot> lockHotspots) {

    // CPU insights
    if (!cpuHotspots.isEmpty()) {
      Hotspot top = cpuHotspots.get(0);
      if (top.getPercentage() > 30) {
        builder.addInsight(String.format("CRITICAL: %s.%s consumes %.1f%% of CPU time (likely bottleneck)",
            top.getClassName(), top.getMethodName(), top.getPercentage()));
        builder.addRecommendation(String.format("Investigate %s.%s at %s - this method is the primary hotspot",
            top.getClassName(), top.getMethodName(), top.getSourceLocation()));
      }
    }

    // Allocation insights
    if (!allocationHotspots.isEmpty()) {
      for (Hotspot hotspot : allocationHotspots.subList(0, Math.min(5, allocationHotspots.size()))) {
        long mb = hotspot.getSampleCount() / (1024 * 1024);
        if (mb > 100) {
          builder.addInsight(
              String.format("MEMORY: %s.%s allocates %d MB", hotspot.getClassName(), hotspot.getMethodName(), mb));
          builder.addRecommendation(String.format("Consider object pooling or reuse in %s.%s", hotspot.getClassName(),
              hotspot.getMethodName()));
        }
      }
    }

    // Lock insights
    if (!lockHotspots.isEmpty()) {
      for (Hotspot hotspot : lockHotspots.subList(0, Math.min(3, lockHotspots.size()))) {
        long ms = hotspot.getSampleCount();
        if (ms > 1000) {
          builder.addInsight(String.format("LOCK CONTENTION: %s.%s blocked for %d ms", hotspot.getClassName(),
              hotspot.getMethodName(), ms));
          builder.addRecommendation(String.format("Review lock usage in %s.%s for contention", hotspot.getClassName(),
              hotspot.getMethodName()));
        }
      }
    }
  }
}
