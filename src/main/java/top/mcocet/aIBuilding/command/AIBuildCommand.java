package top.mcocet.aIBuilding.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mcocet.aIBuilding.AIBuilding;
import top.mcocet.aIBuilding.ai.BuildSession;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AIBuildCommand implements CommandExecutor, TabCompleter {

    private final AIBuilding plugin;

    public AIBuildCommand(AIBuilding plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        // reload 允许控制台与任何有权限者执行
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("aibuilding.reload")) {
                sender.sendMessage("§c你没有权限执行此命令。");
                return true;
            }
            plugin.reloadConfig();
            sender.sendMessage("§b[AI建筑] §f配置已重新加载。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§b[AI建筑] §f用法: /" + label + " <建筑描述> | /" + label + " stop | /" + label + " continue | /" + label + " clear | /" + label + " reload");
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) {
            if (plugin.stopSession(player.getUniqueId())) {
                player.sendMessage("§b[AI建筑] §f已停止当前建筑任务。");
            } else {
                player.sendMessage("§b[AI建筑] §f你没有正在进行的建筑任务。");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("continue")) {
            BuildSession session = plugin.getSession(player.getUniqueId());
            if (session == null || !session.resume()) {
                player.sendMessage("§b[AI建筑] §f没有等待确认继续的建筑任务。");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("clear")) {
            boolean removed = plugin.getContextStore().clear(player.getUniqueId());
            player.sendMessage(removed
                    ? "§b[AI建筑] §f已清除你的 AI 上下文记忆。"
                    : "§b[AI建筑] §f没有找到你的上下文记忆。");
            return true;
        }
        if (plugin.hasSession(player.getUniqueId())) {
            player.sendMessage("§b[AI建筑] §f你已有正在进行的建筑任务，请先使用 /" + label + " stop 停止。");
            return true;
        }
        String prompt = String.join(" ", args);
        plugin.startSession(player, prompt);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("stop", "continue", "clear", "reload");
        }
        return Collections.emptyList();
    }
}
