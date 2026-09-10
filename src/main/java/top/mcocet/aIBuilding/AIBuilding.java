package top.mcocet.aIBuilding;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.aIBuilding.ai.BuildSession;
import top.mcocet.aIBuilding.ai.OpenAIClient;
import top.mcocet.aIBuilding.ai.ToolManager;
import top.mcocet.aIBuilding.command.AIBuildCommand;
import top.mcocet.aIBuilding.util.ContextStore;
import top.mcocet.aIBuilding.util.LogBuffer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class AIBuilding extends JavaPlugin {

    private OpenAIClient openAIClient;
    private ToolManager toolManager;
    private LogBuffer logBuffer;
    private ContextStore contextStore;
    private final Map<UUID, BuildSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        logBuffer = new LogBuffer(getConfig().getInt("safety.log-buffer-size", 500));
        // 挂到根日志器以捕获全部控制台输出
        Logger.getLogger("").addHandler(logBuffer);

        openAIClient = new OpenAIClient(this);
        toolManager = new ToolManager(this);
        contextStore = new ContextStore(this);

        AIBuildCommand command = new AIBuildCommand(this);
        PluginCommand pluginCommand = getCommand("aibuild");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        getLogger().info("AIBuilding 已启用，AI 接口: "
                + getConfig().getString("ai.api-base", "https://api.openai.com/v1")
                + "，模型: " + getConfig().getString("ai.model", "gpt-4o-mini"));
    }

    @Override
    public void onDisable() {
        sessions.values().forEach(BuildSession::cancel);
        sessions.clear();
        if (logBuffer != null) {
            Logger.getLogger("").removeHandler(logBuffer);
        }
        getLogger().info("AIBuilding 已禁用。");
    }

    /**
     * 为玩家启动一次 AI 建筑会话。
     */
    public void startSession(Player player, String prompt) {
        BuildSession session = new BuildSession(this, player);
        sessions.put(player.getUniqueId(), session);
        session.start(prompt);
    }

    /**
     * 停止玩家的会话，返回是否存在会话。
     */
    public boolean stopSession(UUID playerId) {
        BuildSession session = sessions.remove(playerId);
        if (session != null) {
            session.cancel();
            return true;
        }
        return false;
    }

    /**
     * 会话自然结束时由 BuildSession 自身调用。
     */
    public void endSession(UUID playerId) {
        sessions.remove(playerId);
    }

    public boolean hasSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /**
     * 获取玩家当前进行中的会话（无则返回 null）。
     */
    public BuildSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public OpenAIClient getOpenAIClient() {
        return openAIClient;
    }

    public ToolManager getToolManager() {
        return toolManager;
    }

    public LogBuffer getLogBuffer() {
        return logBuffer;
    }

    public ContextStore getContextStore() {
        return contextStore;
    }
}
