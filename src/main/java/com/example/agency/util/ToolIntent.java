package com.example.agency.util;

import java.util.Map;
import java.util.Collections;

public record ToolIntent(String toolName, Map<String, Object> arguments) {
    public ToolIntent(String toolName) {
        this(toolName, Collections.emptyMap());
    }

    public boolean isToolIdentified() {
        return toolName != null && !toolName.isEmpty();
    }

    public boolean hasArguments() {
        return arguments != null && !arguments.isEmpty();
    }
}