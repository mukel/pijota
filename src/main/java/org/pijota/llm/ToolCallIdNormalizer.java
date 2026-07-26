package org.pijota.llm;

import org.pijota.config.ProviderCatalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

final class ToolCallIdNormalizer {
    private ToolCallIdNormalizer() {}

    static String normalize(String id, String provider) {
        String apiType = ProviderCatalog.apiType(provider);
        return switch (apiType != null ? apiType : provider) {
            case "anthropic", "google" -> constrained(id, 64, true);
            case "openai" -> openAi(id);
            default -> constrained(id, 64, false);
        };
    }

    static String unique(String id, String provider, Set<String> used) {
        String normalized = normalize(id, provider);
        if (used.add(normalized))
            return normalized;

        int limit = limit(provider);
        String suffix = "_" + shortHash(id == null ? "" : id);
        int prefixLimit = Math.max(1, limit - suffix.length());
        String candidate = truncate(normalized, prefixLimit) + suffix;
        int counter = 2;
        while (!used.add(candidate)) {
            String numberedSuffix = suffix + "_" + counter++;
            prefixLimit = Math.max(1, limit - numberedSuffix.length());
            candidate = truncate(normalized, prefixLimit) + numberedSuffix;
        }
        return candidate;
    }

    private static String openAi(String id) {
        String value = id == null ? "" : id;
        int pipe = value.indexOf('|');
        if (pipe >= 0)
            value = value.substring(0, pipe);
        return constrained(value, 40, false);
    }

    private static String constrained(String id, int limit, boolean strict) {
        String value = id == null ? "" : id.trim();
        value = value.replaceAll(strict ? "[^a-zA-Z0-9_-]" : "[\\p{Cntrl}\\s]+", "_");
        value = value.replaceAll("_+", "_");
        value = trimUnderscores(value);
        if (value.isBlank())
            value = "call_" + shortHash(id == null ? "" : id);
        return truncate(value, limit);
    }

    private static int limit(String provider) {
        String apiType = ProviderCatalog.apiType(provider);
        return "openai".equals(apiType) ? 40 : 64;
    }

    private static String trimUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_')
            start++;
        while (end > start && value.charAt(end - 1) == '_')
            end--;
        return value.substring(start, end);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 4; i++)
                sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
