package io.github.fxjcangku.worldseedlab.core.evidence;

/** Write-only JSON Lines v1; deserialization is deferred until the schema is versioned. */
public final class EvidenceJsonLines {
    private EvidenceJsonLines() {
    }

    public static String serialize(EvidenceRecord record) {
        return "{"
                + "\"schema\":1,"
                + "\"id\":" + quoted(record.id().toString()) + ","
                + "\"world_fingerprint\":" + quoted(record.worldFingerprint()) + ","
                + "\"dimension\":" + quoted(record.dimension()) + ","
                + "\"client_version\":" + quoted(record.clientVersion()) + ","
                + "\"kind\":" + quoted(record.kind().name()) + ","
                + "\"observed_identifier\":" + quoted(record.observedIdentifier()) + ","
                + "\"position\":{" 
                + "\"x\":" + record.x() + ","
                + "\"y\":" + record.y() + ","
                + "\"z\":" + record.z() + "},"
                + "\"origin\":" + quoted(record.origin().name()) + ","
                + "\"observed_at\":" + quoted(record.observedAt().toString()) + ","
                + "\"note\":" + quoted(record.note())
                + "}";
    }

    private static String quoted(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length() + 4).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}

