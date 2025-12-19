package io.takari.maven.timeline;

@SuppressWarnings({"FieldCanBeLocal", "unused"}) // needed for serialization
public class Dependency {

    private final String from;
    private final String to;
    private final String scope;
    private final boolean isCriticalPath;

    public Dependency(String from, String to, String scope, boolean isCriticalPath) {
        this.from = from;
        this.to = to;
        this.scope = scope;
        this.isCriticalPath = isCriticalPath;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public String getScope() {
        return scope;
    }

    public boolean isCriticalPath() {
        return isCriticalPath;
    }
}
