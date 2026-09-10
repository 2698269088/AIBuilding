package top.mcocet.aIBuilding.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import top.mcocet.aIBuilding.AIBuilding;
import top.mcocet.aIBuilding.util.BlockHistory;
import top.mcocet.aIBuilding.util.ContextStore;
import top.mcocet.aIBuilding.util.StructureUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 AI 建筑会话：负责多轮对话循环。
 * 流程: 异步请求 AI -> 若有工具调用则在主线程执行 -> 结果回传 -> 再次请求，直至 AI 给出最终回复。
 */
public class BuildSession {

    private final AIBuilding plugin;
    private final Player player;
    private final List<JsonObject> messages = Collections.synchronizedList(new ArrayList<>());
    private final BlockHistory history;
    private volatile StructureUtil.CopiedStructure clipboard;
    private volatile boolean cancelled = false;
    private volatile boolean awaitingContinue = false;
    private BukkitTask continueTimeoutTask;
    private int iterations = 0;
    private final int maxIterations;

    public BuildSession(AIBuilding plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.maxIterations = plugin.getConfig().getInt("ai.max-iterations", 40);
        this.history = new BlockHistory(plugin.getConfig().getInt("safety.history-max-entries", 50));
    }

    public Player getPlayer() {
        return player;
    }

    public BlockHistory getHistory() {
        return history;
    }

    public StructureUtil.CopiedStructure getClipboard() {
        return clipboard;
    }

    public void setClipboard(StructureUtil.CopiedStructure clipboard) {
        this.clipboard = clipboard;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 达到最大轮数后由玩家通过 /aibuild continue 调用，继续当前任务。
     *
     * @return 会话是否处于等待确认状态并成功继续
     */
    public boolean resume() {
        if (cancelled || !awaitingContinue) {
            return false;
        }
        awaitingContinue = false;
        cancelContinueTimeout();
        iterations = 0;
        info("已确认继续，AI 接着建造…");
        step();
        return true;
    }

    public void start(String prompt) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", plugin.getConfig().getString("system-prompt", ""));
        messages.add(system);

        // 载入该玩家的历史上下文，让 AI 记住之前的对话与建筑
        ContextStore contextStore = plugin.getContextStore();
        if (contextStore.isEnabled()) {
            List<JsonObject> historyMessages = contextStore.load(player.getUniqueId());
            historyMessages.forEach(messages::add);
            if (!historyMessages.isEmpty()) {
                info("已载入 " + historyMessages.size() + " 条历史上下文，AI 可以记住之前的任务。");
            }
        }

        Location loc = player.getLocation();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "玩家当前位置: 世界 " + player.getWorld().getName()
                + "，坐标 X=" + loc.getBlockX() + "，Y=" + loc.getBlockY() + "，Z=" + loc.getBlockZ()
                + "。请建造以下建筑: " + prompt);
        messages.add(user);

        info("AI 开始工作，建筑描述: " + prompt);
        step();
    }

    public void cancel() {
        cancelled = true;
        cancelContinueTimeout();
        saveContext();
        info("会话已停止。");
    }

    private void step() {
        if (cancelled) {
            return;
        }
        if (++iterations > maxIterations) {
            askContinue();
            return;
        }

        plugin.getOpenAIClient().chat(messages, plugin.getToolManager().getDefinitions())
                .thenAccept(message -> {
                    if (cancelled) {
                        return;
                    }
                    JsonArray toolCalls = message.has("tool_calls") && message.get("tool_calls").isJsonArray()
                            ? message.getAsJsonArray("tool_calls") : null;
                    String finishReason = message.has("_finish_reason") && message.get("_finish_reason").isJsonPrimitive()
                            ? message.get("_finish_reason").getAsString() : "";
                    if (plugin.getConfig().getBoolean("display.show-tool-calls", true)) {
                        plugin.getLogger().info("玩家 " + player.getName() + " 的会话: 第 " + iterations
                                + " 轮响应 finish_reason=" + (finishReason.isEmpty() ? "未知" : finishReason)
                                + "，工具调用 " + (toolCalls == null ? 0 : toolCalls.size()) + " 个");
                    }

                    // 将 AI 的回复（含 tool_calls）原样追加到对话中
                    JsonObject assistantMessage = new JsonObject();
                    assistantMessage.addProperty("role", "assistant");
                    if (message.has("content") && message.get("content").isJsonPrimitive()) {
                        assistantMessage.addProperty("content", message.get("content").getAsString());
                    }
                    if (toolCalls != null) {
                        assistantMessage.add("tool_calls", toolCalls);
                    }
                    messages.add(assistantMessage);

                    if (toolCalls == null || toolCalls.size() == 0) {
                        String content = message.has("content") && message.get("content").isJsonPrimitive()
                                ? message.get("content").getAsString() : "";
                        if (content.isEmpty()) {
                            // AI 返回空内容：通常是 max-tokens 被思考过程耗尽导致输出截断，或 API 站异常
                            if ("length".equals(finishReason)) {
                                int maxTokens = plugin.getConfig().getInt("ai.max-tokens", 4096);
                                String limitHint = maxTokens > 0
                                        ? "本轮生成达到了 max-tokens 上限（当前 " + maxTokens + "），请在 config.yml 中调大 ai.max-tokens（如 8192 或 16384）"
                                        : "未设置 max-tokens 上限，截断是 API 站或模型自身的输出限制导致";
                                finish("AI 的输出被截断（finish_reason=length）：" + limitHint
                                        + "；若使用的是推理模型（如 DeepSeek-R1），其思考过程也会占用输出额度，建议换用普通模型。对话已保存，修改配置后可继续任务。");
                            } else {
                                finish("AI 返回了空内容（finish_reason=" + (finishReason.isEmpty() ? "未知" : finishReason)
                                        + "），任务结束。若频繁出现，请检查 API 站是否稳定、模型是否支持工具调用。");
                            }
                        } else {
                            finish(content);
                        }
                        return;
                    }

                    // AI 在调用工具前输出的说明性文字（阶段性思考）同步展示给玩家与控制台
                    if (message.has("content") && message.get("content").isJsonPrimitive()) {
                        String content = message.get("content").getAsString().trim();
                        if (!content.isEmpty()) {
                            info("§d" + content);
                        }
                    }

                    // 工具调用必须在主线程执行
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        JsonArray toolResults = new JsonArray();
                        for (JsonElement element : toolCalls) {
                            toolResults.add(executeToolCall(element.getAsJsonObject()));
                        }
                        // 执行完毕后切回异步线程继续对话
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            if (cancelled) {
                                return;
                            }
                            for (JsonElement toolResult : toolResults) {
                                messages.add(toolResult.getAsJsonObject());
                            }
                            step();
                        });
                    });
                })
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    finish("AI 请求失败: " + (cause != null ? cause.getMessage() : ex.getMessage()));
                    return null;
                });
    }

    private JsonObject executeToolCall(JsonObject toolCall) {
        String id = toolCall.has("id") ? toolCall.get("id").getAsString() : "";
        JsonObject function = toolCall.getAsJsonObject("function");
        String name = function.has("name") ? function.get("name").getAsString() : "";

        JsonObject args = new JsonObject();
        if (function.has("arguments") && function.get("arguments").isJsonPrimitive()) {
            String argsStr = function.get("arguments").getAsString();
            try {
                args = JsonParser.parseString(argsStr).getAsJsonObject();
            } catch (Exception e) {
                // 参数 JSON 不完整（可能被 max-tokens 截断），让 AI 重新调用
                JsonObject toolMessage = new JsonObject();
                toolMessage.addProperty("role", "tool");
                toolMessage.addProperty("tool_call_id", id);
                toolMessage.addProperty("content", "{\"success\":false,\"error\":\"工具参数解析失败（可能被 max-tokens 截断），请重新调用 " + name + "\"}");
                return toolMessage;
            }
        }

        if (plugin.getConfig().getBoolean("display.show-tool-calls", true)) {
            info("AI 执行工具: " + name + " " + args);
        }
        String result = plugin.getToolManager().execute(name, args, player.isOnline() ? player : null);

        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        toolMessage.addProperty("tool_call_id", id);
        toolMessage.addProperty("content", result);
        return toolMessage;
    }

    private void finish(String finalText) {
        cancelled = true;
        cancelContinueTimeout();
        saveContext();
        plugin.endSession(player.getUniqueId());
        info("§a" + finalText);
    }

    /**
     * 达到最大交互轮数时暂停任务，询问玩家是否继续。
     */
    private void askContinue() {
        awaitingContinue = true;
        info("已达到最大交互轮数 (" + maxIterations + ")。输入 /aibuild continue 让 AI 继续建造，或 /aibuild stop 停止任务。");
        int timeoutSeconds = plugin.getConfig().getInt("ai.continue-timeout-seconds", 300);
        if (timeoutSeconds > 0) {
            continueTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!cancelled && awaitingContinue) {
                    awaitingContinue = false;
                    cancel();
                    plugin.endSession(player.getUniqueId());
                }
            }, timeoutSeconds * 20L);
        }
    }

    private void cancelContinueTimeout() {
        if (continueTimeoutTask != null) {
            continueTimeoutTask.cancel();
            continueTimeoutTask = null;
        }
    }

    /**
     * 将会话对话历史保存到上下文存储，供下次会话载入。
     */
    private void saveContext() {
        ContextStore contextStore = plugin.getContextStore();
        if (!contextStore.isEnabled()) {
            return;
        }
        try {
            synchronized (messages) {
                contextStore.save(player.getUniqueId(), new ArrayList<>(messages));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("保存 AI 上下文失败: " + e.getMessage());
        }
    }

    private void info(String text) {
        String colored = "§b[AI建筑] §f" + text;
        if (player.isOnline()) {
            player.sendMessage(colored);
        }
        plugin.getLogger().info("玩家 " + player.getName() + " 的会话: " + text);
    }
}
