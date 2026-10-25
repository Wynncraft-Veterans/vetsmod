package org.wynnvets.api;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;

/**
 * Builders for the register, auth, queue-status, chat, rank-change, tab-list and staff-action
 * frames the client sends on the v1 inbound socket. Each returns a new
 * {@link JsonObject} with its keys in wire order; none sends it, logs, or reads or writes
 * connection state. {@link #inbound} and {@link #rankChange} stamp a random {@code uuid} and the
 * current time.
 */
final class V1Frames {

    private V1Frames() {}

    static JsonObject register(String uuid, String username, String tier) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "register");
        payload.addProperty("uuid", uuid);
        payload.addProperty("username", username);
        payload.addProperty("tier", tier);
        return payload;
    }

    static JsonObject auth(String key) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "auth");
        payload.addProperty("key", key);
        return payload;
    }

    static JsonObject queueStatus(boolean queued, String world) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "queue_status");
        payload.addProperty("queued", queued);
        payload.addProperty("world", world != null ? world : "");
        return payload;
    }

    static JsonObject inbound(String type, String rank, String username, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", UUID.randomUUID().toString());
        payload.addProperty("type", type);
        payload.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        payload.addProperty("rank", rank != null ? rank : "");
        payload.addProperty("username", username);
        payload.addProperty("message", message);
        return payload;
    }

    static JsonObject rankChange(
            String actor, String target, String fromRank, String toRank, String classification) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "rank_change");
        payload.addProperty("uuid", UUID.randomUUID().toString());
        payload.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        payload.addProperty("actor", actor);
        payload.addProperty("target", target);
        payload.addProperty("from_rank", fromRank);
        payload.addProperty("to_rank", toRank);
        payload.addProperty("classification", classification);
        return payload;
    }

    static JsonObject tabList(List<V1ApiManager.TabListEntry> entries) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "tablist");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (V1ApiManager.TabListEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("server", e.server());
            obj.addProperty("username", e.username());
            arr.add(obj);
        }
        payload.add("entries", arr);
        return payload;
    }

    static JsonObject staffAction(String type, JsonObject fields) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        if (fields != null) {
            for (var entry : fields.entrySet()) {
                payload.add(entry.getKey(), entry.getValue());
            }
        }
        return payload;
    }
}
