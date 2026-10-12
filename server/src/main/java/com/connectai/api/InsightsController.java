package com.connectai.api;

import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.repository.InvestigationRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measured efficiency of the hybrid engine, computed from stored investigations only:
 * how many runs each route answered, how long they took end to end, and their outcomes.
 * No estimates - every number is derived from persisted timestamps and statuses. Route latency
 * only counts runs that called at least one tool; runs stored before routes were recorded are
 * counted as AI agent runs, which is what they were.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InvestigationRepository investigations;

    public InsightsController(InvestigationRepository investigations) {
        this.investigations = investigations;
    }

    public record RouteStats(long runs, Double medianSeconds, Double p90Seconds, long answered) { }

    public record Efficiency(long completedRuns, Map<String, RouteStats> routes, Double fastPathShare,
                             Double speedup, Map<InvestigationStatus, Long> outcomes, long honestNonAnswers) { }

    @GetMapping("/efficiency")
    @Transactional(readOnly = true)
    public Efficiency efficiency() {
        Map<String, List<Double>> durations = new LinkedHashMap<>();
        Map<String, Long> answered = new LinkedHashMap<>();
        Map<InvestigationStatus, Long> outcomes = new EnumMap<>(InvestigationStatus.class);
        long completed = 0;

        for (Investigation investigation : investigations.findAll()) {
            if (!investigation.getStatus().isTerminal() || investigation.getCompletedAt() == null) {
                continue;
            }
            completed++;
            outcomes.merge(investigation.getStatus(), 1L, Long::sum);
            // Runs stored before routes were recorded all went through the AI agent (no fast path existed).
            String route = investigation.getRoute() == null ? "AI_AGENT" : investigation.getRoute();
            if (investigation.getSteps().isEmpty()) {
                continue; // instant refusals (no tool call) are counted in outcomes but would distort latency
            }
            double seconds = Duration.between(investigation.getCreatedAt(), investigation.getCompletedAt()).toMillis() / 1000.0;
            durations.computeIfAbsent(route, r -> new ArrayList<>()).add(seconds);
            if (investigation.getStatus() == InvestigationStatus.ANSWERED) {
                answered.merge(route, 1L, Long::sum);
            }
        }

        Map<String, RouteStats> routes = new LinkedHashMap<>();
        durations.forEach((route, values) -> routes.put(route, new RouteStats(values.size(),
                percentile(values, 50), percentile(values, 90), answered.getOrDefault(route, 0L))));

        long routed = durations.values().stream().mapToLong(List::size).sum();
        long fast = routes.entrySet().stream().filter(e -> !e.getKey().equals("AI_AGENT"))
                .mapToLong(e -> e.getValue().runs()).sum();
        RouteStats ai = routes.get("AI_AGENT");
        RouteStats fastPath = routes.get("FAST_PATH");
        Double speedup = ai != null && fastPath != null && fastPath.medianSeconds() != null && fastPath.medianSeconds() > 0
                ? round(ai.medianSeconds() / fastPath.medianSeconds()) : null;
        long honest = outcomes.getOrDefault(InvestigationStatus.INCONCLUSIVE, 0L)
                + outcomes.getOrDefault(InvestigationStatus.PARTIAL, 0L)
                + outcomes.getOrDefault(InvestigationStatus.FAILED, 0L);

        return new Efficiency(completed, routes, routed == 0 ? null : round((double) fast / routed), speedup, outcomes, honest);
    }

    static Double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return round(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    private static Double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
