/**
 * Copyright (C) 2013 david@gageot.net Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License
 */
package io.takari.maven.timeline.buildevents;

import io.takari.maven.timeline.Dependency;
import io.takari.maven.timeline.Event;
import io.takari.maven.timeline.Timeline;
import io.takari.maven.timeline.TimelineSerializer;
import io.takari.maven.timeline.WebUtils;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// adjacent bars should be a different color
// highlight the critical path
// table with build values that are sortable

public final class BuildEventListener extends AbstractExecutionListener {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final File mavenTimeline;
    private final String artifactId;
    private final String groupId;
    private final File output;
    private final long start;
    private final Map<Execution, Metric> executionMetrics = new ConcurrentHashMap<>();
    private final Map<Execution, Event> timelineMetrics = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> threadToTrackNum = new ConcurrentHashMap<>();
    private final Map<Long, Integer> threadNumToColour = new ConcurrentHashMap<>();
    private final AtomicLong trackNum = new AtomicLong(0);
    private final Map<String, MavenProject> projects = new LinkedHashMap<>();
    private final Map<String, Long> projectStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> projectEndTimes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> projectDependencies = new ConcurrentHashMap<>();

    private final long startTime;

    public BuildEventListener(File output, File mavenTimeline, String artifactId, String groupId) {
        this.output = output;
        this.mavenTimeline = mavenTimeline;
        this.artifactId = artifactId;
        this.groupId = groupId;
        this.start = System.currentTimeMillis();
        this.startTime = nowInUtc();
    }

    private long millis() {
        return System.currentTimeMillis() - start;
    }

    @Override
    public void mojoStarted(ExecutionEvent event) {
        Execution key = key(event);
        Long threadId = Thread.currentThread().getId();
        AtomicLong threadTrackNum = threadToTrackNum.get(threadId);
        if (threadTrackNum == null) {
            // use this since we can not computeIfAbsent() yet
            synchronized (this) {
                //noinspection ConstantConditions
                if (threadTrackNum == null) {
                    threadTrackNum = new AtomicLong(trackNum.getAndIncrement());
                    threadToTrackNum.put(threadId, threadTrackNum);
                }
            }
        }
        Integer colour = threadNumToColour.get(threadId);
        if (colour == null) {
            colour = 0;
            threadNumToColour.put(threadId, colour);
        } else {
            colour = 1 - colour;
            threadNumToColour.put(threadId, colour);
        }
        executionMetrics.put(key, new Metric(key, Thread.currentThread().getId(), millis()));
        timelineMetrics.put(
                key,
                new Event(
                        threadTrackNum.get(),
                        nowInUtc(),
                        key.groupId,
                        key.artifactId,
                        key.mojoGroupId,
                        key.mojoArtifactId,
                        key.phase,
                        key.goal,
                        key.id));
    }

    private long nowInUtc() {
        return System.currentTimeMillis();
    }

    @Override
    public void projectStarted(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project != null) {
            String projectKey = getProjectKey(project);
            projects.put(projectKey, project);
            projectStartTimes.put(projectKey, nowInUtc());

            // Collect dependencies using getProjectReferences which is safe API
            List<String> deps = new ArrayList<>();
            if (project.getProjectReferences() != null) {
                for (Object refObj : project.getProjectReferences().keySet()) {
                    String refKey = refObj.toString();
                    // Project references are in the format "groupId:artifactId:version"
                    // We need just "groupId:artifactId"
                    String[] parts = refKey.split(":");
                    if (parts.length >= 2) {
                        deps.add(parts[0] + ":" + parts[1]);
                    }
                }
            }
            projectDependencies.put(projectKey, deps);
        }
    }

    @Override
    public void projectSucceeded(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project != null) {
            String projectKey = getProjectKey(project);
            projectEndTimes.put(projectKey, nowInUtc());
        }
    }

    @Override
    public void projectFailed(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project != null) {
            String projectKey = getProjectKey(project);
            projectEndTimes.put(projectKey, nowInUtc());
        }
    }

    @Override
    public void mojoSkipped(ExecutionEvent event) {
        mojoEnd(event);
    }

    @Override
    public void mojoSucceeded(ExecutionEvent event) {
        mojoEnd(event);
    }

    @Override
    public void mojoFailed(ExecutionEvent event) {
        mojoEnd(event);
    }

    private void mojoEnd(ExecutionEvent event) {
        final Event timelineMetric = timelineMetrics.get(key(event));
        final Metric metric = executionMetrics.get(key(event));
        if (metric == null) {
            return;
        }
        metric.setEnd(millis());
        timelineMetric.setEnd(System.currentTimeMillis());
        timelineMetric.setDuration(metric.end - metric.start);
    }

    @Override
    public void sessionEnded(ExecutionEvent event) {
        try {
            report();
        } catch (IOException e) {
            logger.warn("Failed to save timeline metrics", e);
        }
    }

    private Execution key(ExecutionEvent event) {
        final MojoExecution mojo = event.getMojoExecution();
        final MavenProject project = event.getProject();
        return new Execution(
                project.getGroupId(),
                project.getArtifactId(),
                mojo.getGroupId(),
                mojo.getArtifactId(),
                mojo.getLifecyclePhase(),
                mojo.getGoal(),
                mojo.getExecutionId());
    }

    private String getProjectKey(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }

    private List<Dependency> collectDependencies() {
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> criticalPathEdges = calculateCriticalPath();

        for (Map.Entry<String, List<String>> entry : projectDependencies.entrySet()) {
            String fromKey = entry.getKey();
            for (String toKey : entry.getValue()) {
                // Only include dependencies that are part of the reactor build
                if (projects.containsKey(toKey)) {
                    String edgeKey = toKey + "->" + fromKey;
                    boolean isCritical = criticalPathEdges.contains(edgeKey);
                    dependencies.add(new Dependency(toKey, fromKey, "compile", isCritical));
                }
            }
        }

        return dependencies;
    }

    private Set<String> calculateCriticalPath() {
        Set<String> criticalPathEdges = new HashSet<>();

        if (projects.isEmpty()) {
            return criticalPathEdges;
        }

        // Calculate longest path by duration
        Map<String, Long> longestPathToNode = new HashMap<>();
        Map<String, String> predecessorOnLongestPath = new HashMap<>();

        // Initialize all projects with their durations
        for (String projectKey : projects.keySet()) {
            Long startTime = projectStartTimes.get(projectKey);
            Long endTime = projectEndTimes.get(projectKey);
            if (startTime != null && endTime != null) {
                longestPathToNode.put(projectKey, endTime - startTime);
            } else {
                longestPathToNode.put(projectKey, 0L);
            }
        }

        // Calculate longest paths considering dependencies
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, List<String>> entry : projectDependencies.entrySet()) {
                String projectKey = entry.getKey();
                Long projectDuration = getDuration(projectKey);

                for (String depKey : entry.getValue()) {
                    if (projects.containsKey(depKey)) {
                        Long depLongestPath = longestPathToNode.get(depKey);
                        Long currentLongestPath = longestPathToNode.get(projectKey);

                        if (depLongestPath != null && currentLongestPath != null) {
                            Long newPath = depLongestPath + projectDuration;
                            if (newPath > currentLongestPath) {
                                longestPathToNode.put(projectKey, newPath);
                                predecessorOnLongestPath.put(projectKey, depKey);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // Find the node with the longest path
        String endNode = null;
        Long maxPath = 0L;
        for (Map.Entry<String, Long> entry : longestPathToNode.entrySet()) {
            if (entry.getValue() > maxPath) {
                maxPath = entry.getValue();
                endNode = entry.getKey();
            }
        }

        // Backtrack to find all edges on the critical path
        String currentNode = endNode;
        while (currentNode != null && predecessorOnLongestPath.containsKey(currentNode)) {
            String predecessor = predecessorOnLongestPath.get(currentNode);
            criticalPathEdges.add(predecessor + "->" + currentNode);
            currentNode = predecessor;
        }

        return criticalPathEdges;
    }

    private Long getDuration(String projectKey) {
        Long startTime = projectStartTimes.get(projectKey);
        Long endTime = projectEndTimes.get(projectKey);
        if (startTime != null && endTime != null) {
            return endTime - startTime;
        }
        return 0L;
    }

    private void report() throws IOException {
        File path = output.getParentFile();
        if (!(path.isDirectory() || path.mkdirs())) {
            throw new IOException("Unable to create " + path);
        }

        try (Writer writer = Files.newBufferedWriter(output.toPath())) {
            Metric.array(writer, executionMetrics.values());
        }

        exportTimeline();
    }

    private void exportTimeline() throws IOException {
        long endTime = nowInUtc();
        WebUtils.copyResourcesToDirectory(getClass(), "timeline", mavenTimeline.getParentFile());
        try (Writer mavenTimelineWriter = Files.newBufferedWriter(mavenTimeline.toPath())) {
            List<Dependency> dependencies = collectDependencies();
            Timeline timeline = new Timeline(
                    startTime, endTime, groupId, artifactId, new ArrayList<>(timelineMetrics.values()), dependencies);
            mavenTimelineWriter.write("window.timelineData = ");
            TimelineSerializer.serialize(mavenTimelineWriter, timeline);
            mavenTimelineWriter.write(";");
        }
    }

    //
    //
    //

    static class Execution {
        final String groupId;
        final String artifactId;
        final String mojoGroupId;
        final String mojoArtifactId;
        final String phase;
        final String goal;
        final String id;

        Execution(
                String groupId,
                String artifactId,
                String mojoGroupId,
                String mojoArtifactId,
                String phase,
                String goal,
                String id) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.mojoGroupId = mojoGroupId;
            this.mojoArtifactId = mojoArtifactId;
            this.phase = phase;
            this.goal = goal;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Execution execution = (Execution) o;

            if (!gaCoordinatesEqual(groupId, artifactId, execution.groupId, execution.artifactId)
                    || !gaCoordinatesEqual(
                            mojoGroupId, mojoArtifactId, execution.mojoGroupId, execution.mojoArtifactId)) {
                return false;
            }

            if (phase != null ? !phase.equals(execution.phase) : execution.phase != null) return false;
            //noinspection SimplifiableIfStatement
            if (goal != null ? !goal.equals(execution.goal) : execution.goal != null) return false;
            return id != null ? id.equals(execution.id) : execution.id == null;
        }

        @Override
        public int hashCode() {
            int result = groupId != null ? groupId.hashCode() : 0;
            result = 31 * result + (artifactId != null ? artifactId.hashCode() : 0);
            result = 31 * result + (mojoGroupId != null ? mojoGroupId.hashCode() : 0);
            result = 31 * result + (mojoArtifactId != null ? mojoArtifactId.hashCode() : 0);
            result = 31 * result + (phase != null ? phase.hashCode() : 0);
            result = 31 * result + (goal != null ? goal.hashCode() : 0);
            result = 31 * result + (id != null ? id.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + mojoGroupId + ":" + mojoArtifactId + ":" + phase + ":" + goal
                    + ":" + id;
        }

        private boolean gaCoordinatesEqual(
                String ourGroup, String ourArtifact, String theirGroup, String theirArtifact) {
            if (ourGroup != null ? !ourGroup.equals(theirGroup) : theirGroup != null) return false;
            if (ourArtifact != null ? !ourArtifact.equals(theirArtifact) : theirArtifact != null) return false;

            return true;
        }
    }

    static class Metric {
        final Execution execution;
        final Long threadId;
        final Long start;
        Long end;

        Metric(Execution execution, Long threadId, Long start) {
            this.execution = execution;
            this.threadId = threadId;
            this.start = start;
        }

        void setEnd(Long end) {
            this.end = end;
        }

        String toJSON() {
            return record(
                    value("groupId", execution.groupId),
                    value("artifactId", execution.artifactId),
                    value("mojoGroupId", execution.mojoGroupId),
                    value("mojoArtifactId", execution.mojoArtifactId),
                    value("phase", execution.phase),
                    value("goal", execution.goal),
                    value("id", execution.id),
                    value("threadId", threadId),
                    value("start", start),
                    value("end", end));
        }

        private String value(String key, String value) {
            return "\"" + key + "\":\"" + value + "\"";
        }

        private String value(String key, Long value) {
            return "\"" + key + "\":" + value + "";
        }

        private String record(String... values) {
            StringBuilder b = new StringBuilder();
            b.append("{");
            for (String value : values) {
                b.append(value).append(",");
            }
            return b.deleteCharAt(b.length() - 1).append("}").toString();
        }

        static void array(Appendable a, Iterable<Metric> metrics) throws IOException {
            a.append("[");
            Iterator<Metric> it = metrics.iterator();
            if (it.hasNext()) {
                a.append(it.next().toJSON());
            }
            while (it.hasNext()) {
                a.append(",").append(it.next().toJSON());
            }
            a.append("]");
        }
    }
}
