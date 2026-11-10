package com.wbox.app;

import org.json.*;
import java.util.Locale;

final class SubscriptionUsage {
    static JSONObject parse(String header) throws JSONException {
        JSONObject result = new JSONObject();
        for (String field : (header == null ? "" : header).split("[;,]")) {
            String[] pair = field.trim().split("=", 2);
            if (pair.length != 2) continue;
            String key = pair[0].trim().toLowerCase(Locale.ROOT), text = pair[1].trim();
            if (!java.util.List.of("upload", "download", "total", "expire").contains(key) || !text.matches("[0-9]+")) continue;
            try {
                long value = Long.parseLong(text);
                if (!key.equals("expire") || value <= 253402300799L) result.put(key, value);
            } catch (NumberFormatException ignored) { }
        }
        return result.length() == 0 ? null : result;
    }

    static void apply(Profiles p, String id, String url, JSONObject usage) throws Exception {
        JSONObject options = SubscriptionUpdates.options(p, id, url);
        if (options != null) options.put("usage", usage);
    }
}
