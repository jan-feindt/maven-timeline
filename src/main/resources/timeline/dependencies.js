/**
 * Module for rendering dependency arrows between artifacts in the timeline
 */

// Constants for track layout (must match timeline.js)
var TRACK_HEIGHT = 60;
var TRACK_MIDDLE_OFFSET = 30;

function DependencyRenderer(timelineData) {
  var dependencies = timelineData.dependencies || [];
  var events = timelineData.events || [];
  var sessionStartTime = timelineData.start;
  
  // Create a map of artifact keys to events for quick lookup
  var artifactToEvents = {};
  
  function buildArtifactMap() {
    artifactToEvents = {};
    for (var i = 0; i < events.length; i++) {
      var event = events[i];
      var key = event.groupId + ":" + event.artifactId;
      if (!artifactToEvents[key]) {
        artifactToEvents[key] = [];
      }
      artifactToEvents[key].push(event);
    }
  }
  
  /**
   * Get the first event (earliest start time) for an artifact
   */
  function getFirstEventForArtifact(artifactKey) {
    var eventList = artifactToEvents[artifactKey];
    if (!eventList || eventList.length === 0) return null;
    
    var firstEvent = eventList[0];
    for (var i = 1; i < eventList.length; i++) {
      if (eventList[i].start < firstEvent.start) {
        firstEvent = eventList[i];
      }
    }
    return firstEvent;
  }
  
  /**
   * Get the last event (latest end time) for an artifact
   */
  function getLastEventForArtifact(artifactKey) {
    var eventList = artifactToEvents[artifactKey];
    if (!eventList || eventList.length === 0) return null;
    
    var lastEvent = eventList[0];
    for (var i = 1; i < eventList.length; i++) {
      if (eventList[i].end > lastEvent.end) {
        lastEvent = eventList[i];
      }
    }
    return lastEvent;
  }
  
  /**
   * Calculate position in pixels
   * Returns minimum of 1 pixel to ensure visibility of even very short events
   */
  function normalize(absoluteStart, relativeStart, zoomFactor) {
    return Math.max(1, Math.abs((relativeStart - absoluteStart) / zoomFactor));
  }
  
  /**
   * Create SVG path for arrow between two points
   */
  function createArrowPath(x1, y1, x2, y2) {
    // Calculate control points for Bézier curve
    var dx = x2 - x1;
    var dy = y2 - y1;
    
    // Use quadratic Bézier curve for smooth arc
    var cpx = x1 + dx * 0.5;
    var cpy = y1 + dy * 0.5 + Math.abs(dy) * 0.3;
    
    return "M " + x1 + " " + y1 + " Q " + cpx + " " + cpy + " " + x2 + " " + y2;
  }
  
  /**
   * Render all dependency arrows
   */
  this.render = function(zoomFactor, showDependencies, showCriticalPath) {
    buildArtifactMap();
    
    var container = document.getElementById("timeLineContainer");
    if (!container) return;
    
    // Remove existing SVG if present
    var existingSvg = document.getElementById("dependencyArrows");
    if (existingSvg) {
      existingSvg.remove();
    }
    
    // Don't render if both are disabled
    if (!showDependencies && !showCriticalPath) {
      return;
    }
    
    // Create SVG container
    var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("id", "dependencyArrows");
    svg.setAttribute("class", "dependency-arrows-svg");
    
    // Define arrowhead markers
    var defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
    
    // Regular arrow marker
    var regularMarker = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    regularMarker.setAttribute("id", "arrowhead-regular");
    regularMarker.setAttribute("markerWidth", "10");
    regularMarker.setAttribute("markerHeight", "10");
    regularMarker.setAttribute("refX", "9");
    regularMarker.setAttribute("refY", "3");
    regularMarker.setAttribute("orient", "auto");
    
    var regularPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
    regularPath.setAttribute("d", "M0,0 L0,6 L9,3 z");
    regularPath.setAttribute("fill", "rgba(0,0,0,0.2)");
    regularMarker.appendChild(regularPath);
    defs.appendChild(regularMarker);
    
    // Critical path arrow marker
    var criticalMarker = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    criticalMarker.setAttribute("id", "arrowhead-critical");
    criticalMarker.setAttribute("markerWidth", "12");
    criticalMarker.setAttribute("markerHeight", "12");
    criticalMarker.setAttribute("refX", "11");
    criticalMarker.setAttribute("refY", "3");
    criticalMarker.setAttribute("orient", "auto");
    
    var criticalPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
    criticalPath.setAttribute("d", "M0,0 L0,6 L9,3 z");
    criticalPath.setAttribute("fill", "#ff5722");
    criticalMarker.appendChild(criticalPath);
    defs.appendChild(criticalMarker);
    
    svg.appendChild(defs);
    
    // Render arrows for each dependency
    for (var i = 0; i < dependencies.length; i++) {
      var dep = dependencies[i];
      
      // Skip based on visibility settings
      if (dep.isCriticalPath && !showCriticalPath) continue;
      if (!dep.isCriticalPath && !showDependencies) continue;
      
      var fromEvent = getLastEventForArtifact(dep.from);
      var toEvent = getFirstEventForArtifact(dep.to);
      
      if (!fromEvent || !toEvent) continue;
      
      // Calculate positions
      var x1 = normalize(sessionStartTime, fromEvent.end, zoomFactor);
      var y1 = fromEvent.trackNum * TRACK_HEIGHT + TRACK_MIDDLE_OFFSET; // Middle of the track
      
      var x2 = normalize(sessionStartTime, toEvent.start, zoomFactor);
      var y2 = toEvent.trackNum * TRACK_HEIGHT + TRACK_MIDDLE_OFFSET;
      
      // Create path element
      var path = document.createElementNS("http://www.w3.org/2000/svg", "path");
      path.setAttribute("d", createArrowPath(x1, y1, x2, y2));
      
      if (dep.isCriticalPath) {
        path.setAttribute("class", "dependency-arrow critical-path");
        path.setAttribute("marker-end", "url(#arrowhead-critical)");
      } else {
        path.setAttribute("class", "dependency-arrow regular");
        path.setAttribute("marker-end", "url(#arrowhead-regular)");
      }
      
      // Add title for tooltip
      path.appendChild(createTitle(dep.from + " → " + dep.to + (dep.isCriticalPath ? " (Critical Path)" : "")));
      
      svg.appendChild(path);
    }
    
    // Insert SVG as first child so arrows appear behind events
    container.insertBefore(svg, container.firstChild);
  };
  
  function createTitle(text) {
    var title = document.createElementNS("http://www.w3.org/2000/svg", "title");
    title.textContent = text;
    return title;
  }
}
