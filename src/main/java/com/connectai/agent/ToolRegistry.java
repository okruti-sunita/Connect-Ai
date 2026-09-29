package com.connectai.agent;

import com.connectai.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Knows which tools are connected. Spring injects every EvidenceTool bean into the constructor. */
@Component
public class ToolRegistry {

    private final Map<SourceType, EvidenceTool> tools = new EnumMap<>(SourceType.class);

    public ToolRegistry(List<EvidenceTool> available) {
        for (EvidenceTool tool : available) {
            EvidenceTool previous = tools.put(tool.source(), tool);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two tools are registered for " + tool.source() + ": "
                                + previous.getClass().getSimpleName() + " and " + tool.getClass().getSimpleName());
            }
        }
    }

    public Optional<EvidenceTool> find(SourceType source) {
        return Optional.ofNullable(tools.get(source));
    }

    public Set<SourceType> connected() {
        return Set.copyOf(tools.keySet());
    }
}
