package top.mcocet.aIBuilding.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.mcocet.aIBuilding.AIBuilding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 兼容 OpenAI 协议的 Chat Completions 客户端。
 * 支持自定义 api-base，可对接任意兼容 OpenAI 协议的 API 站。
 */
public class OpenAIClient {

    private final AIBuilding plugin;
    private final HttpClient http;

    public OpenAIClient(AIBuilding plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 发送一次聊天请求（携带工具定义），返回 choices[0].message。
     */
    public CompletableFuture<JsonObject> chat(List<JsonObject> messages, JsonArray tools) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject request = new JsonObject();
            request.addProperty("model", plugin.getConfig().getString("ai.model", "gpt-4o-mini"));
            request.addProperty("temperature", plugin.getConfig().getDouble("ai.temperature", 0.2));
            int maxTokens = plugin.getConfig().getInt("ai.max-tokens", 4096);
            if (maxTokens > 0) {
                request.addProperty("max_tokens", maxTokens);
            }
            JsonArray msgArray = new JsonArray();
            messages.forEach(msgArray::add);
            request.add("messages", msgArray);
            if (tools != null && tools.size() > 0) {
                request.add("tools", tools);
                request.addProperty("tool_choice", "auto");
            }

            String base = apiBase();
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofSeconds(plugin.getConfig().getInt("ai.request-timeout-seconds", 120)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .build();

            try {
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException("API 请求失败 (HTTP " + response.statusCode() + "): "
                            + truncate(response.body(), 500));
                }
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray choices = body.getAsJsonArray("choices");
                if (choices == null || choices.size() == 0) {
                    throw new RuntimeException("API 返回结果中没有 choices");
                }
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                if (message == null) {
                    throw new RuntimeException("API 返回的 choices[0] 缺少 message 字段: "
                            + truncate(response.body(), 300));
                }
                // 附加 finish_reason 供上层判断输出是否被 max-tokens 截断
                if (choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive()) {
                    message.addProperty("_finish_reason", choice.get("finish_reason").getAsString());
                }
                if (plugin.getConfig().getBoolean("display.show-tool-calls", true)
                        && body.has("usage") && body.get("usage").isJsonObject()) {
                    JsonObject usage = body.getAsJsonObject("usage");
                    plugin.getLogger().info("API 用量: prompt=" + jsonInt(usage, "prompt_tokens")
                            + " completion=" + jsonInt(usage, "completion_tokens")
                            + " total=" + jsonInt(usage, "total_tokens"));
                }
                return message;
            } catch (IOException e) {
                throw new RuntimeException("网络请求失败: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("网络请求被中断", e);
            }
        });
    }

    private String apiBase() {
        String base = plugin.getConfig().getString("ai.api-base", "https://api.openai.com/v1");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String apiKey() {
        return plugin.getConfig().getString("ai.api-key", "");
    }

    private static int jsonInt(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
