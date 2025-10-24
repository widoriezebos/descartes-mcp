package com.bitsapplied.descartes.profiler.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.bitsapplied.descartes.profiler.model.CallTreeNode;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;

/**
 * Exports profiler data as interactive HTML flame graphs.
 *
 * <p>
 * Flame graphs visualize call stacks with:
 * <ul>
 * <li>Width = time/samples spent in method</li>
 * <li>Height = stack depth</li>
 * <li>Interactive zoom, search, and tooltips</li>
 * <li>Color-coded by package/class</li>
 * </ul>
 */
public class FlameGraphExporter {

  private static final int CANVAS_WIDTH = 1200; // Base width for calculations, actual display is 100%
  private static final int RECT_HEIGHT = 32; // MUCH larger bars exactly like Datadog
  private static final int TOP_PADDING = 60; // Increased for header and subtitle

  /**
   * Export profile snapshot as interactive HTML flame graph.
   *
   * @param snapshot Profile snapshot to export
   * @return HTML string with embedded SVG and JavaScript
   */
  public String exportToHtml(ProfileSnapshot snapshot) {
    StringBuilder html = new StringBuilder();

    // HTML header
    html.append(generateHtmlHeader(snapshot));

    // Generate SVG flame graph
    html.append("<svg id=\"flamegraph\" width=\"").append(CANVAS_WIDTH).append("\">\n");

    // Generate flame graph data as JavaScript array
    List<FrameData> frames = new ArrayList<>();
    int maxDepth = 0;

    // Calculate total samples represented in call trees (may be less than
    // snapshot.getTotalSamples())
    long totalRootSamples = snapshot.getCallTrees().values().stream().mapToLong(CallTreeNode::getHitCount).sum();

    // Use totalRootSamples for scaling so bars fill full width even if some samples
    // are unattributed
    long scalingDivisor = totalRootSamples > 0 ? totalRootSamples : snapshot.getTotalSamples();

    // Process each root call tree - accumulate offset so roots don't overlap
    long cumulativeOffset = 0;
    for (CallTreeNode root : snapshot.getCallTrees().values()) {
      int depth = collectFrames(root, 0, cumulativeOffset, snapshot.getTotalSamples(), frames, scalingDivisor);
      maxDepth = Math.max(maxDepth, depth);
      cumulativeOffset += root.getHitCount(); // Position next root after this one
    }

    int svgHeight = (maxDepth + 1) * RECT_HEIGHT + TOP_PADDING;
    html.append("</svg>\n");

    // Generate JavaScript with frame data
    html.append(generateJavaScript(frames, snapshot, svgHeight));

    // HTML footer
    html.append(generateHtmlFooter());

    return html.toString();
  }

  /**
   * Export to file and return the file path.
   *
   * @param snapshot   Profile snapshot
   * @param outputPath Output file path
   * @return Path to generated file
   * @throws IOException If write fails
   */
  public Path exportToFile(ProfileSnapshot snapshot, Path outputPath) throws IOException {
    String html = exportToHtml(snapshot);
    Files.createDirectories(outputPath.getParent());
    Files.writeString(outputPath, html);
    return outputPath;
  }

  /**
   * Recursively collect frame data from call tree.
   *
   * @param node          Current call tree node
   * @param depth         Stack depth
   * @param offsetSamples Sample offset (X position)
   * @param totalSamples  Total samples in profile
   * @param frames        Output list of frame data
   * @param rootSamples   Samples in root node (for percentage calculation)
   * @return Maximum depth reached
   */
  private int collectFrames(CallTreeNode node, int depth, long offsetSamples, long totalSamples, List<FrameData> frames,
      long rootSamples) {

    long samples = node.getHitCount();
    if (samples == 0) {
      return depth;
    }

    // Create frame
    FrameData frame = new FrameData();
    frame.name = node.getMethodName();
    frame.fullName = node.getMethodSignature();
    frame.className = node.getClassName() != null ? node.getClassName() : "";
    frame.samples = samples;
    frame.percentage = (samples * 100.0) / totalSamples;
    frame.depth = depth;
    frame.x = (offsetSamples * CANVAS_WIDTH) / (double) rootSamples;
    frame.width = (samples * CANVAS_WIDTH) / (double) rootSamples;
    frame.color = getColorForClass(frame.className);

    frames.add(frame);

    // Process children
    int maxDepth = depth;
    long childOffset = offsetSamples;

    for (CallTreeNode child : node.getChildren()) {
      int childDepth = collectFrames(child, depth + 1, childOffset, totalSamples, frames, rootSamples);
      maxDepth = Math.max(maxDepth, childDepth);
      childOffset += child.getHitCount();
    }

    return maxDepth;
  }

  /**
   * Generate consistent color for a class name using hashing. Uses lighter, more
   * pastel colors like Datadog for better text contrast.
   */
  private String getColorForClass(String className) {
    if (className == null || className.isEmpty()) {
      return "hsl(0, 50%, 80%)";
    }

    // Hash class name to get consistent hue
    int hash = className.hashCode();
    int hue = Math.abs(hash % 360);

    // Use Datadog-style colors: medium saturation, medium lightness for contrast
    // with white text
    int saturation = 45 + (hash % 20); // 45-65% saturation
    int lightness = 50 + (hash % 15); // 50-65% lightness (darker for white text contrast)

    return String.format("hsl(%d, %d%%, %d%%)", hue, saturation, lightness);
  }

  private String generateHtmlHeader(ProfileSnapshot snapshot) {
    double cpuTime = (snapshot.getTotalSamples() * snapshot.getMetadata().getConfig().getSamplingIntervalMs()) / 1000.0;
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <title>Flame Graph - %s</title>
          <style>
            body {
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
              margin: 0;
              padding: 0;
              background: #fafafa;
            }
            #header {
              position: sticky;
              top: 0;
              z-index: 100;
              background: white;
            }
            #controls {
              background: white;
              padding: 20px;
              margin-bottom: 0;
              border-bottom: 1px solid #e0e0e0;
            }
            #info {
              margin-bottom: 15px;
              color: #666;
              font-size: 13px;
            }
            #search {
              padding: 8px 12px;
              border: 1px solid #ddd;
              border-radius: 4px;
              width: 300px;
              font-size: 14px;
            }
            #reset, #info-toggle {
              padding: 8px 16px;
              margin-left: 10px;
              background: #6366f1;
              color: white;
              border: none;
              border-radius: 4px;
              cursor: pointer;
              font-size: 14px;
            }
            #reset:hover, #info-toggle:hover {
              background: #4f46e5;
            }
            #flamegraph-container {
              background: white;
              position: relative;
              overflow-x: auto;
              margin: 0;
            }
            #flamegraph {
              background: white;
              display: block;
              width: 100%%;
            }
            .frame {
              cursor: pointer;
              stroke: white;
              stroke-width: 1;
            }
            .frame:hover {
              stroke: #333;
              stroke-width: 2;
            }
            .frame-label {
              font-size: 15px;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
              pointer-events: none;
              fill: #ffffff !important;
              font-weight: 600;
              opacity: 1;
            }
            .search-match {
              fill: #00ff00 !important;
              stroke: #000000 !important;
              stroke-width: 3 !important;
            }
            .search-match-label {
              fill: #000000 !important;
            }
            #frame-info {
              background: #f8fafc;
              border: 1px solid #e2e8f0;
              border-radius: 6px;
              padding: 12px 16px;
              margin: 16px 20px;
              font-size: 13px;
              color: #334155;
              min-height: 60px;
              font-family: monospace;
            }
            #frame-info .label {
              font-weight: 600;
              color: #64748b;
            }
            #frame-info .value {
              color: #0f172a;
            }
            #breadcrumb {
              margin-top: 12px;
              color: #666;
              font-size: 13px;
            }
            .flamegraph-header {
              position: absolute;
              left: 5px;
              top: 5px;
              font-size: 12px;
              font-weight: 600;
              color: #fff;
              background: #dc2626;
              padding: 4px 8px;
              border-radius: 3px;
              pointer-events: none;
              z-index: 10;
            }
            .flamegraph-subtitle {
              position: absolute;
              left: 50%%;
              top: 5px;
              transform: translateX(-50%%);
              font-size: 11px;
              color: #666;
              pointer-events: none;
              z-index: 10;
            }
            .flamegraph-axis-label {
              position: absolute;
              top: 5px;
              font-size: 11px;
              color: #666;
              pointer-events: none;
              z-index: 10;
            }
            .flamegraph-axis-label.left {
              left: 5px;
            }
            .flamegraph-axis-label.right {
              right: 5px;
            }
          </style>
        </head>
        <body>
          <div id="header">
            <div id="controls">
              <div id="info">
                <strong>Profile:</strong> %s |
                <strong>Duration:</strong> %ds |
                <strong>Samples:</strong> %,d |
                <strong>Interval:</strong> %dms |
                <strong>CPU Time:</strong> %.1fs
              </div>
              <input type="text" id="search" placeholder="Search methods (e.g., 'recallSimilar')...">
              <button id="reset">Reset Zoom</button>
              <button id="info-toggle">Show Help</button>
              <div id="breadcrumb"></div>
            </div>
            <div id="frame-info">
              <div style="color: #94a3b8; font-style: italic;">Hover over a frame to see details</div>
            </div>
          </div>
          <div id="flamegraph-container">
        """.formatted(snapshot.getMetadata().getProfileId(), snapshot.getMetadata().getProfileId(),
        snapshot.getDurationSeconds(), snapshot.getTotalSamples(),
        snapshot.getMetadata().getConfig().getSamplingIntervalMs(), cpuTime);
  }

  private String generateJavaScript(List<FrameData> frames, ProfileSnapshot snapshot, int svgHeight) {
    StringBuilder js = new StringBuilder();
    js.append("<script>\n");

    // Embed frame data as JSON with original coordinates for zoom filtering
    js.append("const framesData = [\n");
    for (int i = 0; i < frames.size(); i++) {
      FrameData f = frames.get(i);
      js.append(String.format(Locale.US,
          "  {name:'%s',fullName:'%s',className:'%s',samples:%d,pct:%.2f,depth:%d,x:%.2f,width:%.2f,originalX:%.2f,originalWidth:%.2f,originalDepth:%d,color:'%s'}",
          escapeJs(f.name), escapeJs(f.fullName), escapeJs(f.className), f.samples, f.percentage, f.depth, f.x, f.width,
          f.x, f.width, f.depth, f.color));
      if (i < frames.size() - 1)
        js.append(",\n");
    }
    js.append("\n];\n\n");

    // JavaScript for rendering and interactivity
    js.append(
        """
            const svg = document.getElementById('flamegraph');
            const container = document.getElementById('flamegraph-container');
            svg.setAttribute('height', %d);

            let currentRoot = null;
            let searchTerm = '';
            let svgWidth = 0;

            // Update SVG width to match container
            function updateWidth() {
              svgWidth = container.clientWidth;
              svg.setAttribute('width', svgWidth);
            }

            window.addEventListener('resize', () => {
              updateWidth();
              render(currentRoot);
            });

            // Clear frame info when leaving the SVG entirely
            svg.addEventListener('mouseleave', clearFrameInfo);

            function render(rootFrame = null) {
              updateWidth();
              svg.innerHTML = '';
              currentRoot = rootFrame;

              const visibleFrames = getVisibleFrames(rootFrame);
              const maxDepth = Math.max(...visibleFrames.map(f => f.depth));

              // Adjust SVG height
              const svgHeight = (maxDepth + 1) * %d + %d;
              svg.setAttribute('height', svgHeight);

              // Calculate scaling factor for current width
              const scale = svgWidth / %d;

              // Add header background bar
              const headerBg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
              headerBg.setAttribute('x', 0);
              headerBg.setAttribute('y', 0);
              headerBg.setAttribute('width', svgWidth);
              headerBg.setAttribute('height', 20);
              headerBg.setAttribute('fill', '#8b5cf6');
              svg.appendChild(headerBg);

              // Add CPU Time header label
              const headerText = document.createElementNS('http://www.w3.org/2000/svg', 'text');
              headerText.setAttribute('x', 8);
              headerText.setAttribute('y', 14);
              headerText.setAttribute('fill', 'white');
              headerText.setAttribute('font-size', '12px');
              headerText.setAttribute('font-weight', '600');
              headerText.textContent = 'CPU Time (%.1fs over %ds)';
              svg.appendChild(headerText);

              // Add subtitle
              const subtitle = document.createElementNS('http://www.w3.org/2000/svg', 'text');
              subtitle.setAttribute('x', svgWidth / 2);
              subtitle.setAttribute('y', 35);
              subtitle.setAttribute('text-anchor', 'middle');
              subtitle.setAttribute('fill', '#666');
              subtitle.setAttribute('font-size', '11px');
              subtitle.textContent = 'Frame width represents the CPU time per method';
              svg.appendChild(subtitle);

              // Add axis labels
              const leftLabel = document.createElementNS('http://www.w3.org/2000/svg', 'text');
              leftLabel.setAttribute('x', 5);
              leftLabel.setAttribute('y', 35);
              leftLabel.setAttribute('fill', '#666');
              leftLabel.setAttribute('font-size', '11px');
              leftLabel.textContent = '0%%';
              svg.appendChild(leftLabel);

              const rightLabel = document.createElementNS('http://www.w3.org/2000/svg', 'text');
              rightLabel.setAttribute('x', svgWidth - 5);
              rightLabel.setAttribute('y', 35);
              rightLabel.setAttribute('text-anchor', 'end');
              rightLabel.setAttribute('fill', '#666');
              rightLabel.setAttribute('font-size', '11px');
              rightLabel.textContent = '100%% (%.1fs)';
              svg.appendChild(rightLabel);

              // Render frames
              visibleFrames.forEach(frame => {
                const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');

                // Scale frame positions
                const frameX = frame.x * scale;
                const frameWidth = frame.width * scale;

                // Rectangle
                const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                rect.setAttribute('x', frameX);
                rect.setAttribute('y', (maxDepth - frame.depth) * %d + %d);
                rect.setAttribute('width', Math.max(0, frameWidth));
                rect.setAttribute('height', %d);
                rect.setAttribute('fill', frame.color);
                rect.setAttribute('class', 'frame');

                if (searchTerm && frame.fullName.toLowerCase().includes(searchTerm.toLowerCase())) {
                  rect.classList.add('search-match');
                }

                // Append rect FIRST so text renders on top
                g.appendChild(rect);

                // Text label (show if wide enough - at least 15px for minimal text)
                if (frameWidth > 15) {
                  const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
                  text.setAttribute('x', frameX + 5);
                  text.setAttribute('y', (maxDepth - frame.depth) * %d + %d + 21); // Centered in 32px bar
                  text.setAttribute('class', 'frame-label');

                  if (searchTerm && frame.fullName.toLowerCase().includes(searchTerm.toLowerCase())) {
                    text.classList.add('search-match-label');
                  }

                  // Calculate visible characters based on width
                  const charWidth = 9.5; // Approximate character width for 15px bold font
                  const maxChars = Math.floor((frameWidth - 10) / charWidth);
                  const label = frame.name.length > maxChars ?
                    frame.name.substring(0, Math.max(1, maxChars - 3)) + '...' :
                    frame.name;
                  text.textContent = label;
                  g.appendChild(text);
                }

                // Event handlers
                g.addEventListener('click', () => {
                  render(frame);
                  updateBreadcrumb();
                });

                g.addEventListener('mouseenter', () => showFrameInfo(frame));
                g.addEventListener('mouseleave', clearFrameInfo);

                svg.appendChild(g);
              });

              updateBreadcrumb();
            }

            function getVisibleFrames(rootFrame) {
              if (!rootFrame) return framesData;

              // Find all frames that are descendants of rootFrame
              // IMPORTANT: Use original coordinates for filtering to support multi-level zoom
              const minDepth = rootFrame.originalDepth;
              const descendants = framesData.filter(f =>
                f.originalDepth >= minDepth &&
                f.originalX >= rootFrame.originalX &&
                f.originalX < rootFrame.originalX + rootFrame.originalWidth
              );

              // Recalculate display positions relative to new root
              return descendants.map(f => ({
                ...f,
                x: ((f.originalX - rootFrame.originalX) / rootFrame.originalWidth) * %d,
                width: (f.originalWidth / rootFrame.originalWidth) * %d,
                depth: f.originalDepth - minDepth
              }));
            }

            function showFrameInfo(frame) {
              const frameInfo = document.getElementById('frame-info');
              frameInfo.innerHTML = `
                <div><span class="label">Method:</span> <span class="value">${frame.fullName}</span></div>
                <div><span class="label">Samples:</span> <span class="value">${frame.samples.toLocaleString()} (${frame.pct.toFixed(2)}%%)</span></div>
                <div><span class="label">Class:</span> <span class="value">${frame.className}</span></div>
              `;
            }

            function clearFrameInfo() {
              const frameInfo = document.getElementById('frame-info');
              frameInfo.innerHTML = '<div style="color: #94a3b8; font-style: italic;">Hover over a frame to see details</div>';
            }

            function updateBreadcrumb() {
              const breadcrumb = document.getElementById('breadcrumb');
              if (!currentRoot) {
                breadcrumb.textContent = 'Viewing: All frames';
              } else {
                breadcrumb.textContent = `Zoomed into: ${currentRoot.fullName} (${currentRoot.pct.toFixed(2)}%%)`;
              }
            }

            // Search functionality
            document.getElementById('search').addEventListener('input', (e) => {
              searchTerm = e.target.value;
              render(currentRoot);
            });

            // Reset button
            document.getElementById('reset').addEventListener('click', () => {
              searchTerm = '';
              document.getElementById('search').value = '';
              render(null);
            });

            // Help button
            document.getElementById('info-toggle').addEventListener('click', () => {
              alert(
                'Flame Graph Help:\\n\\n' +
                '• Click any frame to zoom into that method\\n' +
                '• Use search box to highlight methods\\n' +
                '• Hover for details (method, samples, percentage)\\n' +
                '• Click Reset Zoom to return to full view\\n' +
                '• Width = time/samples spent in method\\n' +
                '• Y-axis = stack depth (root at bottom)'
              );
            });

            // Initial render
            render();
            </script>
            """
            .formatted(svgHeight, RECT_HEIGHT, TOP_PADDING, CANVAS_WIDTH,
                (snapshot.getTotalSamples() * snapshot.getMetadata().getConfig().getSamplingIntervalMs()) / 1000.0,
                snapshot.getDurationSeconds(),
                (snapshot.getTotalSamples() * snapshot.getMetadata().getConfig().getSamplingIntervalMs()) / 1000.0,
                RECT_HEIGHT, TOP_PADDING, RECT_HEIGHT, RECT_HEIGHT, TOP_PADDING, CANVAS_WIDTH, CANVAS_WIDTH));

    return js.toString();
  }

  private String generateHtmlFooter() {
    return """
          </div>
        </body>
        </html>
        """;
  }

  private String escapeJs(String str) {
    if (str == null)
      return "";
    return str.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
  }

  /**
   * Frame data for SVG rendering.
   */
  private static class FrameData {
    String name;
    String fullName;
    String className;
    long samples;
    double percentage;
    int depth;
    double x;
    double width;
    String color;
  }
}
