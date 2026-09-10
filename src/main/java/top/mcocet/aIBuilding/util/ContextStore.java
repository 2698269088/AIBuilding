package top.mcocet.aIBuilding.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.mcocet.aIBuilding.AIBuilding;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * AI 上下文记忆：按玩家把对话历史持久化到插件数据文件夹，
 * 新会话启动时载入最近的历史，让 AI 记住之前的对话与建筑。
 * 裁剪历史时保证 OpenAI 工具调用链的完整性。
 */
public class ContextStore {

    private final AIBuilding plugin;

    public ContextStore(AIBuilding plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("ai.context-memory", true);
    }

    /**
     * 载入玩家的历史消息（按条数与字数预算裁剪，且工具调用链完整）。
     */
    public List<JsonObject> load(UUID playerId) {
        return trim(readAll(playerId), maxMessages(), maxChars());
    }

    /**
     * 保存玩家的对话历史（排除 system 消息，自动裁剪）。
     */
    public void save(UUID playerId, List<JsonObject> messages) {
        List<JsonObject> withoutSystem = new ArrayList<>();
        for (JsonObject message : messages) {
            if (!"system".equals(roleOf(message))) {
                withoutSystem.add(message);
            }
        }
        List<JsonObject> trimmed = trim(withoutSystem, maxMessages(), maxChars());
        File file = fileFor(playerId);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("创建上下文目录失败: " + parent.getAbsolutePath());
            return;
        }
        JsonArray array = new JsonArray();
        trimmed.forEach(array::add);
        try {
            Files.write(file.toPath(), array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("保存 AI 上下文失败: " + e.getMessage());
        }
    }

    /**
     * 清除玩家的上下文记忆。
     *
     * @return 是否清除了已有文件
     */
    public boolean clear(UUID playerId) {
        File file = fileFor(playerId);
        return file.exists() && file.delete();
    }

    private List<JsonObject> readAll(UUID playerId) {
        List<JsonObject> messages = new ArrayList<>();
        File file = fileFor(playerId);
        if (!file.isFile()) {
            return messages;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonArray()) {
                return messages;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonObject() && element.getAsJsonObject().has("role")) {
                    messages.add(element.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取 AI 上下文失败: " + e.getMessage());
        }
        return messages;
    }

    /**
     * 裁剪历史：丢弃末尾未完成的工具调用，按预算从头部裁剪，并对齐到用户消息边界，
     * 最后移除因裁剪产生的孤儿 tool 消息，确保发给 API 的消息序列合法。
     */
    private static List<JsonObject> trim(List<JsonObject> messages, int maxMessages, int maxChars) {
        int end = messages.size();
        // 1. 丢弃末尾未完成的工具调用（assistant 发出了 tool_calls 但会话中断，缺少 tool 结果）
        while (end > 0) {
            JsonObject last = messages.get(end - 1);
            if ("assistant".equals(roleOf(last)) && last.has("tool_calls")) {
                end--;
            } else {
                break;
            }
        }
        // 2. 从后往前按条数与字数预算裁剪
        int start = end;
        int chars = 0;
        while (start > 0 && end - start < maxMessages) {
            int len = messages.get(start - 1).toString().length();
            if (chars + len > maxChars && start < end) {
                break;
            }
            chars += len;
            start--;
        }
        // 3. 对齐到 user 边界，保证从一条完整的用户请求开始
        while (start < end && !"user".equals(roleOf(messages.get(start)))) {
            start++;
        }
        // 4. 移除链被截断的孤儿 tool 消息
        return validate(messages.subList(start, end));
    }

    private static List<JsonObject> validate(List<JsonObject> window) {
        Set<String> callIds = new HashSet<>();
        for (JsonObject message : window) {
            if ("assistant".equals(roleOf(message)) && message.has("tool_calls")) {
                for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                    JsonObject call = element.getAsJsonObject();
                    if (call.has("id")) {
                        callIds.add(call.get("id").getAsString());
                    }
                }
            }
        }
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject message : window) {
            if ("tool".equals(roleOf(message))) {
                String id = message.has("tool_call_id") ? message.get("tool_call_id").getAsString() : "";
                if (!callIds.contains(id)) {
                    continue;
                }
            }
            result.add(message);
        }
        return result;
    }

    private static String roleOf(JsonObject message) {
        return message.has("role") && message.get("role").isJsonPrimitive()
                ? message.get("role").getAsString() : "";
    }

    private int maxMessages() {
        return plugin.getConfig().getInt("ai.context-max-messages", 30);
    }

    private int maxChars() {
        return plugin.getConfig().getInt("ai.context-max-chars", 20000);
    }

    private File fileFor(UUID playerId) {
        return new File(plugin.getDataFolder(), "context" + File.separator + playerId + ".json");
    }
}
