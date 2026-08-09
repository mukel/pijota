package org.pijota.agent;

import org.pijota.llm.ModelRegistry;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tokens and cost for a session, broken down by provider and model, for {@code /usage} and the session picker. */
public final class SessionUsage {
    private SessionUsage() {}

    public record Breakdown(String key, long tokens, double cost) {
    }

    public static List<Breakdown> breakdown(List<Message> messages, String fallbackProvider, String fallbackModel) {
        var totals = new LinkedHashMap<String, Totals>();
        for (Message msg : messages) {
            if (!(msg instanceof Message.Assistant assistant) || assistant.usage() == null)
                continue;
            String provider = blankToDefault(assistant.sourceProvider(), fallbackProvider);
            String model = blankToDefault(assistant.sourceModel(), fallbackModel);
            String key = provider + "/" + model;
            var total = totals.computeIfAbsent(key, ignored -> new Totals());
            total.add(assistant.usage(), ModelRegistry.pricingFor(provider, model));
        }
        var out = new ArrayList<Breakdown>();
        totals.forEach((key, total) -> out.add(new Breakdown(key, total.tokens, total.cost)));
        out.sort((a, b) -> {
            int byCost = Double.compare(b.cost, a.cost);
            return byCost != 0 ? byCost : Long.compare(b.tokens, a.tokens);
        });
        return List.copyOf(out);
    }

    public static List<Breakdown> compactionBreakdown(List<Map<String, Object>> entries) {
        var totals = new Totals();
        for (var entry : entries) {
            if (!"compaction".equals(entry.get("type")))
                continue;
            Usage usage = usageFrom(entry.get("usage"));
            if (usage != null)
                totals.add(usage, null);
        }
        return totals.tokens > 0 ? List.of(new Breakdown("Tools/summaries", totals.tokens, totals.cost)) : List.of();
    }

    public static String formatCost(double cost) {
        return String.format(Locale.ROOT, "~$%.4f", cost);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback == null || fallback.isBlank() ? "unknown" : fallback : value;
    }

    static Usage usageFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> m))
            return null;
        return new Usage(asLong(m.get("inputTokens")), asLong(m.get("outputTokens")), asLong(m.get("cacheReadTokens")),
                                        asLong(m.get("cacheWriteTokens")), asLong(m.get("reasoningTokens")));
    }

    static long asLong(Object value) {
        if (value instanceof Number n)
            return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static final class Totals {
        long tokens;
        double cost;

        void add(Usage usage, ModelRegistry.Pricing pricing) {
            long input = usage.inputTokens();
            long output = usage.outputTokens();
            long cached = usage.cacheReadTokens() + usage.cacheWriteTokens();
            tokens += input + output;
            if (pricing == null)
                return;
            var rates = pricing.ratesFor(input + cached);
            cost += (input / 1_000_000.0) * rates.inputPerMTok() + (output / 1_000_000.0) * rates.outputPerMTok();
        }
    }
}
