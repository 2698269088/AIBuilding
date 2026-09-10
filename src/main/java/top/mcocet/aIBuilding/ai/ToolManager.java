package top.mcocet.aIBuilding.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import top.mcocet.aIBuilding.AIBuilding;
import top.mcocet.aIBuilding.util.BlockHistory;
import top.mcocet.aIBuilding.util.NbtUtil;
import top.mcocet.aIBuilding.util.StructureUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 可调用的工具集合（OpenAI Function Calling 协议）。
 * 所有工具均须在服务器主线程执行。
 */
public class ToolManager {

    private final AIBuilding plugin;
    private final JsonArray definitions;

    public ToolManager(AIBuilding plugin) {
        this.plugin = plugin;
        this.definitions = buildDefinitions();
    }

    public JsonArray getDefinitions() {
        return definitions;
    }

    /**
     * 执行一个工具调用，返回作为 tool message 内容发送给 AI 的 JSON 字符串。
     */
    public String execute(String name, JsonObject args, Player player) {
        try {
            switch (name) {
                case "place_block":
                    return placeBlock(args, player);
                case "fill_blocks":
                    return fillBlocks(args, player);
                case "get_block":
                    return getBlock(args, player);
                case "compare_block":
                    return compareBlock(args, player);
                case "get_block_nbt":
                    return getBlockNbt(args, player);
                case "set_block_nbt":
                    return setBlockNbt(args, player);
                case "run_command":
                    return runCommand(args);
                case "get_console_logs":
                    return getConsoleLogs(args);
                case "list_files":
                    return listFiles();
                case "read_file":
                    return readFile(args);
                case "replace_blocks":
                    return replaceBlocks(args, player);
                case "move_blocks":
                    return moveBlocks(args, player);
                case "copy_region":
                    return copyRegion(args, player);
                case "paste_region":
                    return pasteRegion(args, player);
                case "generate_shape":
                    return generateShape(args, player);
                case "get_region_summary":
                    return getRegionSummary(args, player);
                case "inspect_region":
                    return inspectRegion(args, player);
                case "set_environment":
                    return setEnvironment(args, player);
                case "get_player_position":
                    return getPlayerPosition(player);
                case "undo":
                    return undo(player);
                default:
                    return error("未知工具: " + name);
            }
        } catch (Exception e) {
            return error("工具执行异常: " + String.valueOf(e.getMessage()));
        }
    }

    // ==================== 工具实现 ====================

    private String placeBlock(JsonObject a, Player player) {
        Integer x = getInt(a, "x"), y = getInt(a, "y"), z = getInt(a, "z");
        String block = getString(a, "block");
        if (x == null || y == null || z == null || block == null) {
            return error("缺少必要参数 x/y/z/block");
        }
        World world = resolveWorld(a, player);
        if (!checkHeight(world, y)) {
            return error("Y 坐标超出世界高度范围");
        }
        BlockData newData;
        try {
            newData = parseBlockData(block, getString(a, "block_data"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        Block target = world.getBlockAt(x, y, z);
        List<BlockHistory.Snapshot> changes = new ArrayList<>();
        snapshotIfChanged(changes, target, newData);
        recordHistory(player, world, changes);
        JsonObject result = ok("已在 (" + x + ", " + y + ", " + z + ") 放置 " + target.getType().name());
        result.addProperty("block_data", target.getBlockData().getAsString());
        return result.toString();
    }

    private String fillBlocks(JsonObject a, Player player) {
        Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
        Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
        String block = getString(a, "block");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null || block == null) {
            return error("缺少必要参数 x1/y1/z1/x2/y2/z2/block");
        }
        World world = resolveWorld(a, player);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        if (!checkHeight(world, minY) || !checkHeight(world, maxY)) {
            return error("Y 坐标超出世界高度范围");
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        String limitError = checkVolume(volume);
        if (limitError != null) {
            return limitError;
        }
        BlockData data;
        try {
            data = parseBlockData(block, getString(a, "block_data"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        List<BlockHistory.Snapshot> changes = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    snapshotIfChanged(changes, world.getBlockAt(x, y, z), data);
                }
            }
        }
        recordHistory(player, world, changes);
        JsonObject result = ok("已填充 " + changes.size() + " 个方块 (" + minX + "," + minY + "," + minZ + ") -> ("
                + maxX + "," + maxY + "," + maxZ + ")");
        result.addProperty("volume", changes.size());
        return result.toString();
    }

    private String getBlock(JsonObject a, Player player) {
        Integer x = getInt(a, "x"), y = getInt(a, "y"), z = getInt(a, "z");
        if (x == null || y == null || z == null) {
            return error("缺少必要参数 x/y/z");
        }
        World world = resolveWorld(a, player);
        Block target = world.getBlockAt(x, y, z);
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("world", world.getName());
        result.addProperty("type", target.getType().name());
        result.addProperty("block_data", target.getBlockData().getAsString());
        return result.toString();
    }

    private String compareBlock(JsonObject a, Player player) {
        Integer x = getInt(a, "x"), y = getInt(a, "y"), z = getInt(a, "z");
        String block = getString(a, "block");
        if (x == null || y == null || z == null || block == null) {
            return error("缺少必要参数 x/y/z/block");
        }
        World world = resolveWorld(a, player);
        Block target = world.getBlockAt(x, y, z);
        Material expected = parseMaterial(block);
        if (expected == null) {
            return error("未知方块类型: " + block);
        }
        boolean matches = target.getType() == expected;
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("matches", matches);
        result.addProperty("expected", expected.name());
        result.addProperty("actual", target.getType().name());
        result.addProperty("block_data", target.getBlockData().getAsString());
        return result.toString();
    }

    private String getBlockNbt(JsonObject a, Player player) {
        Integer x = getInt(a, "x"), y = getInt(a, "y"), z = getInt(a, "z");
        if (x == null || y == null || z == null) {
            return error("缺少必要参数 x/y/z");
        }
        if (!NbtUtil.isAvailable()) {
            return error("当前服务端版本不支持 NBT 读取");
        }
        World world = resolveWorld(a, player);
        Block target = world.getBlockAt(x, y, z);
        try {
            String nbt = NbtUtil.getBlockNbt(target);
            if (nbt == null) {
                JsonObject result = ok("该方块没有 NBT 数据（非方块实体），其方块状态为: "
                        + target.getBlockData().getAsString());
                result.addProperty("has_nbt", false);
                result.addProperty("block_data", target.getBlockData().getAsString());
                return result.toString();
            }
            JsonObject result = ok("NBT 获取成功");
            result.addProperty("has_nbt", true);
            result.addProperty("nbt", nbt);
            return result.toString();
        } catch (Exception e) {
            return error("读取 NBT 失败: " + e.getMessage());
        }
    }

    private String setBlockNbt(JsonObject a, Player player) {
        Integer x = getInt(a, "x"), y = getInt(a, "y"), z = getInt(a, "z");
        String nbt = getString(a, "nbt");
        if (x == null || y == null || z == null || nbt == null) {
            return error("缺少必要参数 x/y/z/nbt");
        }
        if (!NbtUtil.isAvailable()) {
            return error("当前服务端版本不支持 NBT 修改");
        }
        World world = resolveWorld(a, player);
        Block target = world.getBlockAt(x, y, z);
        try {
            boolean success = NbtUtil.setBlockNbt(target, nbt);
            if (!success) {
                return error("该方块没有方块实体，无法写入 NBT");
            }
            return ok("NBT 写入成功").toString();
        } catch (Exception e) {
            return error("写入 NBT 失败（请检查 SNBT 格式是否正确）: " + e.getMessage());
        }
    }

    private String runCommand(JsonObject a) {
        String command = getString(a, "command");
        if (command == null || command.isEmpty()) {
            return error("缺少必要参数 command");
        }
        if (!plugin.getConfig().getBoolean("safety.allow-server-command", true)) {
            return error("服务器已禁止 AI 执行命令（config.yml 中 safety.allow-server-command=false）");
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        JsonObject result = ok("命令已执行，输出请通过 get_console_logs 工具查看");
        result.addProperty("command", command);
        return result.toString();
    }

    private String getConsoleLogs(JsonObject a) {
        Integer lines = getInt(a, "lines");
        if (lines == null) {
            lines = 30;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : plugin.getLogBuffer().getLastLines(lines)) {
            sb.append(line).append('\n');
        }
        JsonObject result = ok("日志获取成功");
        result.addProperty("logs", sb.toString());
        return result.toString();
    }

    private String listFiles() {
        if (!plugin.getConfig().getBoolean("safety.allow-file-read", true)) {
            return error("服务器已禁止 AI 读取文件（config.yml 中 safety.allow-file-read=false）");
        }
        File folder = plugin.getDataFolder();
        if (!folder.exists()) {
            return error("插件配置文件夹不存在");
        }
        JsonArray files = new JsonArray();
        collectFiles(folder, folder, files);
        JsonObject result = ok("文件列表获取成功，共 " + files.size() + " 个文件");
        result.add("files", files);
        return result.toString();
    }

    private static void collectFiles(File base, File dir, JsonArray out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(base, child, out);
            } else {
                JsonObject file = new JsonObject();
                file.addProperty("path", base.toPath().relativize(child.toPath()).toString().replace('\\', '/'));
                file.addProperty("size", child.length());
                out.add(file);
            }
        }
    }

    private String readFile(JsonObject a) {
        String pathStr = getString(a, "path");
        if (pathStr == null || pathStr.isEmpty()) {
            return error("缺少必要参数 path");
        }
        if (!plugin.getConfig().getBoolean("safety.allow-file-read", true)) {
            return error("服务器已禁止 AI 读取文件（config.yml 中 safety.allow-file-read=false）");
        }
        // 只允许读取插件配置文件夹内的文件，阻止路径穿越
        Path base = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = base.resolve(pathStr).normalize();
        if (!target.startsWith(base)) {
            return error("路径越界，只能读取插件配置文件夹内的文件");
        }
        if (!Files.isRegularFile(target)) {
            return error("文件不存在: " + pathStr);
        }
        try {
            long maxSize = plugin.getConfig().getLong("safety.max-file-size", 1048576);
            long size = Files.size(target);
            if (size > maxSize) {
                return error("文件大小 " + size + " 字节超过上限 " + maxSize + " 字节");
            }
            byte[] bytes = Files.readAllBytes(target);
            for (byte b : bytes) {
                if (b == 0) {
                    return error("该文件是二进制文件，无法以文本形式读取");
                }
            }
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

            Integer maxLines = getInt(a, "lines");
            if (maxLines == null) {
                maxLines = plugin.getConfig().getInt("safety.max-file-lines", 500);
            }
            String[] lines = content.split("\n", -1);
            boolean truncated = lines.length > maxLines;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
                sb.append(lines[i]).append('\n');
            }

            JsonObject result = ok("文件读取成功");
            result.addProperty("path", base.relativize(target).toString().replace('\\', '/'));
            result.addProperty("total_lines", lines.length);
            result.addProperty("truncated", truncated);
            result.addProperty("content", sb.toString());
            return result.toString();
        } catch (Exception e) {
            return error("文件读取失败: " + e.getMessage());
        }
    }

    // ==================== 高级工具实现 ====================

    private String replaceBlocks(JsonObject a, Player player) {
        Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
        Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
        String block = getString(a, "block");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null || block == null) {
            return error("缺少必要参数 x1/y1/z1/x2/y2/z2/block");
        }
        World world = resolveWorld(a, player);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        if (!checkHeight(world, minY) || !checkHeight(world, maxY)) {
            return error("Y 坐标超出世界高度范围");
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        String limitError = checkVolume(volume);
        if (limitError != null) {
            return limitError;
        }

        String filter = getString(a, "filter");
        Material filterMaterial = null;
        if (filter != null) {
            filterMaterial = parseMaterial(filter);
            if (filterMaterial == null) {
                return error("未知过滤方块类型: " + filter);
            }
        }
        BlockData data;
        try {
            data = parseBlockData(block, getString(a, "block_data"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        List<BlockHistory.Snapshot> changes = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block target = world.getBlockAt(x, y, z);
                    boolean match = filterMaterial == null
                            ? target.getType() != Material.AIR
                            : target.getType() == filterMaterial;
                    if (match) {
                        snapshotIfChanged(changes, target, data);
                    }
                }
            }
        }
        recordHistory(player, world, changes);
        JsonObject result = ok("已替换 " + changes.size() + " 个方块");
        result.addProperty("replaced", changes.size());
        return result.toString();
    }

    private String moveBlocks(JsonObject a, Player player) {
        Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
        Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
        Integer dx = getInt(a, "dx"), dy = getInt(a, "dy"), dz = getInt(a, "dz");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null
                || dx == null || dy == null || dz == null) {
            return error("缺少必要参数 x1/y1/z1/x2/y2/z2/dx/dy/dz");
        }
        if (!StructureUtil.isAvailable()) {
            return error("当前服务端不支持区域平移（StructureTemplate 不可用）");
        }
        World world = resolveWorld(a, player);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        if (!checkHeight(world, minY + dy) || !checkHeight(world, maxY + dy)) {
            return error("平移后的 Y 坐标超出世界高度范围");
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        String limitError = checkVolume(volume);
        if (limitError != null) {
            return limitError;
        }

        try {
            StructureUtil.CopiedStructure structure = StructureUtil.copyRegion(world, x1, y1, z1, x2, y2, z2);
            if (structure == null) {
                return error("区域复制失败");
            }
            // 记录原区域与目标区域的现状，便于整体撤销
            List<BlockHistory.Snapshot> original = snapshotRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
            List<BlockHistory.Snapshot> destination = snapshotRegion(
                    world, minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);

            boolean pasted = StructureUtil.paste(world, structure, minX + dx, minY + dy, minZ + dz, null, null);
            if (!pasted) {
                return error("区域粘贴失败");
            }
            // 清空原区域
            BlockData air = Material.AIR.createBlockData();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block target = world.getBlockAt(x, y, z);
                        if (target.getType() != Material.AIR) {
                            target.setBlockData(air, false);
                        }
                    }
                }
            }
            List<BlockHistory.Snapshot> combined = new ArrayList<>(original);
            combined.addAll(destination);
            recordHistory(player, world, combined);
            return ok("已将区域平移 (" + dx + ", " + dy + ", " + dz + ")，共移动 " + original.size() + " 个方块")
                    .toString();
        } catch (Exception e) {
            return error("平移区域失败: " + e.getMessage());
        }
    }

    private String copyRegion(JsonObject a, Player player) {
        Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
        Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            return error("缺少必要参数 x1/y1/z1/x2/y2/z2");
        }
        if (!StructureUtil.isAvailable()) {
            return error("当前服务端不支持区域复制（StructureTemplate 不可用）");
        }
        if (player == null) {
            return error("该工具需要玩家上下文");
        }
        BuildSession session = plugin.getSession(player.getUniqueId());
        if (session == null) {
            return error("当前没有进行中的建筑会话");
        }
        World world = resolveWorld(a, player);
        long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        String limitError = checkVolume(volume);
        if (limitError != null) {
            return limitError;
        }
        try {
            StructureUtil.CopiedStructure structure = StructureUtil.copyRegion(world, x1, y1, z1, x2, y2, z2);
            if (structure == null) {
                return error("区域复制失败");
            }
            session.setClipboard(structure);
            return ok("已复制区域，尺寸 " + structure.sizeX + "x" + structure.sizeY + "x" + structure.sizeZ
                    + "，可用 paste_region 粘贴").toString();
        } catch (Exception e) {
            return error("复制区域失败: " + e.getMessage());
        }
    }

    private String pasteRegion(JsonObject a, Player player) {
        Integer x = getInt(a, "x"), y = getInt(a, "y"), z = getInt(a, "z");
        if (x == null || y == null || z == null) {
            return error("缺少必要参数 x/y/z");
        }
        if (!StructureUtil.isAvailable()) {
            return error("当前服务端不支持区域粘贴（StructureTemplate 不可用）");
        }
        if (player == null) {
            return error("该工具需要玩家上下文");
        }
        BuildSession session = plugin.getSession(player.getUniqueId());
        StructureUtil.CopiedStructure structure = session != null ? session.getClipboard() : null;
        if (structure == null) {
            return error("剪贴板为空，请先使用 copy_region 复制一个区域");
        }
        World world = resolveWorld(a, player);

        String rotation = getString(a, "rotation");
        String rot = rotation == null ? "none" : rotation.toLowerCase();
        int sizeX = structure.sizeX;
        int sizeZ = structure.sizeZ;
        if (rot.equals("cw90") || rot.equals("ccw90") || rot.equals("clockwise_90")
                || rot.equals("counterclockwise_90")) {
            int temp = sizeX;
            sizeX = sizeZ;
            sizeZ = temp;
        }
        if (!checkHeight(world, y) || !checkHeight(world, y + structure.sizeY - 1)) {
            return error("Y 坐标超出世界高度范围");
        }
        long volume = (long) sizeX * structure.sizeY * sizeZ;
        String limitError = checkVolume(volume);
        if (limitError != null) {
            return limitError;
        }

        try {
            // 记录粘贴区域的现状，撤销时恢复
            List<BlockHistory.Snapshot> snapshots = snapshotRegion(
                    world, x, y, z, x + sizeX - 1, y + structure.sizeY - 1, z + sizeZ - 1);
            boolean pasted = StructureUtil.paste(world, structure, x, y, z, rotation, getString(a, "mirror"));
            if (!pasted) {
                return error("粘贴失败");
            }
            recordHistory(player, world, snapshots);
            return ok("已将剪贴板结构粘贴到 (" + x + ", " + y + ", " + z + ")，尺寸 "
                    + sizeX + "x" + structure.sizeY + "x" + sizeZ).toString();
        } catch (Exception e) {
            return error("粘贴失败: " + e.getMessage());
        }
    }

    private String generateShape(JsonObject a, Player player) {
        String shape = getString(a, "shape");
        String block = getString(a, "block");
        if (shape == null || block == null) {
            return error("缺少必要参数 shape/block");
        }
        World world = resolveWorld(a, player);
        BlockData data;
        try {
            data = parseBlockData(block, getString(a, "block_data"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        List<BlockHistory.Snapshot> changes = new ArrayList<>();
        String type = shape.toLowerCase();
        switch (type) {
            case "sphere":
            case "hemisphere": {
                Integer cx = getInt(a, "cx"), cy = getInt(a, "cy"), cz = getInt(a, "cz");
                Integer radius = getInt(a, "radius");
                if (cx == null || cy == null || cz == null || radius == null) {
                    return error("缺少必要参数 cx/cy/cz/radius");
                }
                if (radius < 1 || radius > 64) {
                    return error("radius 需在 1-64 之间");
                }
                long bound = (long) (2 * radius + 1) * (2 * radius + 1) * (2 * radius + 1);
                String limitError = checkVolume(bound);
                if (limitError != null) {
                    return limitError;
                }
                boolean upperOnly = type.equals("hemisphere");
                double rr = (radius + 0.35) * (radius + 0.35);
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        if (upperOnly && dy < 0) {
                            continue;
                        }
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (dx * dx + dy * dy + dz * dz <= rr) {
                                snapshotIfChanged(changes, world.getBlockAt(cx + dx, cy + dy, cz + dz), data);
                            }
                        }
                    }
                }
                break;
            }
            case "cylinder":
            case "circle": {
                Integer cx = getInt(a, "cx"), cy = getInt(a, "cy"), cz = getInt(a, "cz");
                Integer radius = getInt(a, "radius");
                Integer height = getInt(a, "height");
                if (cx == null || cy == null || cz == null || radius == null) {
                    return error("缺少必要参数 cx/cy/cz/radius");
                }
                if (height == null) {
                    height = 1;
                }
                if (radius < 1 || radius > 64 || height < 1 || height > 64) {
                    return error("radius/height 需在 1-64 之间");
                }
                long bound = (long) (2 * radius + 1) * (2 * radius + 1) * height;
                String limitError = checkVolume(bound);
                if (limitError != null) {
                    return limitError;
                }
                double rr = (radius + 0.35) * (radius + 0.35);
                for (int dy = 0; dy < height; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (dx * dx + dz * dz <= rr) {
                                snapshotIfChanged(changes, world.getBlockAt(cx + dx, cy + dy, cz + dz), data);
                            }
                        }
                    }
                }
                break;
            }
            case "pyramid": {
                Integer cx = getInt(a, "cx"), cy = getInt(a, "cy"), cz = getInt(a, "cz");
                Integer base = getInt(a, "base");
                Integer height = getInt(a, "height");
                if (cx == null || cy == null || cz == null || base == null || height == null) {
                    return error("缺少必要参数 cx/cy/cz/base/height");
                }
                if (base < 1 || base > 64 || height < 1 || height > 64) {
                    return error("base/height 需在 1-64 之间");
                }
                long bound = (long) base * base * height;
                String limitError = checkVolume(bound);
                if (limitError != null) {
                    return limitError;
                }
                for (int i = 0; i < height; i++) {
                    int half = Math.round(base / 2f * (1 - (float) i / height));
                    for (int dx = -half; dx <= half; dx++) {
                        for (int dz = -half; dz <= half; dz++) {
                            snapshotIfChanged(changes, world.getBlockAt(cx + dx, cy + i, cz + dz), data);
                        }
                    }
                }
                break;
            }
            case "line": {
                Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
                Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
                Integer thickness = getInt(a, "thickness");
                if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
                    return error("缺少必要参数 x1/y1/z1/x2/y2/z2");
                }
                if (thickness == null) {
                    thickness = 1;
                }
                if (thickness < 1 || thickness > 8) {
                    return error("thickness 需在 1-8 之间");
                }
                double ddx = x2 - x1, ddy = y2 - y1, ddz = z2 - z1;
                double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                if (dist > 512) {
                    return error("线条长度过长（最大 512 格）");
                }
                int steps = Math.max(1, (int) Math.ceil(dist / 0.4));
                int half = thickness / 2;
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    int px = (int) Math.round(x1 + (x2 - x1) * t);
                    int py = (int) Math.round(y1 + (y2 - y1) * t);
                    int pz = (int) Math.round(z1 + (z2 - z1) * t);
                    for (int ox = -half; ox <= half; ox++) {
                        for (int oy = -half; oy <= half; oy++) {
                            for (int oz = -half; oz <= half; oz++) {
                                if (checkHeight(world, py + oy)) {
                                    snapshotIfChanged(changes, world.getBlockAt(px + ox, py + oy, pz + oz), data);
                                }
                            }
                        }
                    }
                }
                break;
            }
            default:
                return error("未知形状: " + shape + "，支持 sphere/hemisphere/cylinder/circle/pyramid/line");
        }

        recordHistory(player, world, changes);
        JsonObject result = ok("已生成 " + type + "，共修改 " + changes.size() + " 个方块");
        result.addProperty("modified", changes.size());
        return result.toString();
    }

    private String inspectRegion(JsonObject a, Player player) {
        Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
        Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            return error("缺少必要参数 x1/y1/z1/x2/y2/z2");
        }
        World world = resolveWorld(a, player);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        long maxInspect = plugin.getConfig().getLong("safety.max-inspect-volume", 32768);
        if (volume > maxInspect) {
            return error("查看体积 " + volume + " 超过上限 " + maxInspect + "，请缩小区域");
        }

        String format = getString(a, "format");
        JsonObject result = ok("区域查看完成");
        result.addProperty("world", world.getName());
        JsonObject min = new JsonObject();
        min.addProperty("x", minX);
        min.addProperty("y", minY);
        min.addProperty("z", minZ);
        result.add("min_corner", min);
        JsonObject size = new JsonObject();
        size.addProperty("x", maxX - minX + 1);
        size.addProperty("y", maxY - minY + 1);
        size.addProperty("z", maxZ - minZ + 1);
        result.add("size", size);

        if (format != null && format.equalsIgnoreCase("list")) {
            // 逐方块清单格式（仅非空气方块）
            JsonArray blocks = new JsonArray();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Material material = world.getBlockAt(x, y, z).getType();
                        if (material != Material.AIR) {
                            JsonObject block = new JsonObject();
                            block.addProperty("x", x);
                            block.addProperty("y", y);
                            block.addProperty("z", z);
                            block.addProperty("type", material.name());
                            blocks.add(block);
                        }
                    }
                }
            }
            result.addProperty("format", "list");
            result.addProperty("block_count", blocks.size());
            result.add("blocks", blocks);
            return result.toString();
        }

        // 默认: 逐层 ASCII 平面图格式，为每种材质分配一个符号
        Map<String, Integer> counts = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material != Material.AIR) {
                        counts.merge(material.name(), 1, Integer::sum);
                    }
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        String pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz@#$%&*+=?";
        Map<String, Character> symbols = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (index >= pool.length()) {
                break;
            }
            symbols.put(entry.getKey(), pool.charAt(index++));
        }

        JsonArray layers = new JsonArray();
        for (int y = minY; y <= maxY; y++) {
            StringBuilder map = new StringBuilder();
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material == Material.AIR) {
                        map.append('.');
                    } else {
                        map.append(symbols.getOrDefault(material.name(), '?'));
                    }
                }
                if (z < maxZ) {
                    map.append('\n');
                }
            }
            JsonObject layer = new JsonObject();
            layer.addProperty("y", y);
            layer.addProperty("map", map.toString());
            layers.add(layer);
        }

        JsonObject legend = new JsonObject();
        for (Map.Entry<String, Character> entry : symbols.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("material", entry.getKey());
            item.addProperty("count", counts.get(entry.getKey()));
            legend.add(String.valueOf(entry.getValue()), item);
        }

        result.addProperty("format", "map");
        result.addProperty("note", "每层为俯视平面图，行对应 Z 轴（由北向南），列对应 X 轴（由西向东），'.' 为空气；坐标 = min_corner + 行列偏移");
        result.add("legend", legend);
        result.add("layers", layers);
        return result.toString();
    }

    private String getRegionSummary(JsonObject a, Player player) {
        Integer x1 = getInt(a, "x1"), y1 = getInt(a, "y1"), z1 = getInt(a, "z1");
        Integer x2 = getInt(a, "x2"), y2 = getInt(a, "y2"), z2 = getInt(a, "z2");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            return error("缺少必要参数 x1/y1/z1/x2/y2/z2");
        }
        World world = resolveWorld(a, player);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        long maxScan = plugin.getConfig().getLong("safety.max-scan-volume", 262144);
        if (volume > maxScan) {
            return error("扫描体积 " + volume + " 超过上限 " + maxScan + "，请缩小区域");
        }

        Map<String, Integer> counts = new HashMap<>();
        long nonAir = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material != Material.AIR) {
                        nonAir++;
                        counts.merge(material.name(), 1, Integer::sum);
                    }
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        JsonObject topBlocks = new JsonObject();
        int limit = Math.min(sorted.size(), 20);
        for (int i = 0; i < limit; i++) {
            topBlocks.addProperty(sorted.get(i).getKey(), sorted.get(i).getValue());
        }
        JsonObject result = ok("区域统计完成");
        result.addProperty("total_scanned", volume);
        result.addProperty("non_air", nonAir);
        result.addProperty("air", volume - nonAir);
        result.add("top_blocks", topBlocks);
        result.addProperty("other_types", sorted.size() - limit);
        return result.toString();
    }

    private String setEnvironment(JsonObject a, Player player) {
        World world = resolveWorld(a, player);
        Integer time = getInt(a, "time");
        String weather = getString(a, "weather");
        if (time == null && weather == null) {
            return error("请至少提供 time 或 weather 参数");
        }
        StringBuilder sb = new StringBuilder();
        if (time != null) {
            world.setTime(time % 24000L);
            sb.append("时间已设为 ").append(time);
        }
        if (weather != null) {
            switch (weather.toLowerCase()) {
                case "clear":
                    world.setStorm(false);
                    world.setThundering(false);
                    break;
                case "rain":
                    world.setStorm(true);
                    world.setThundering(false);
                    break;
                case "thunder":
                    world.setStorm(true);
                    world.setThundering(true);
                    break;
                default:
                    return error("未知天气: " + weather + "，支持 clear/rain/thunder");
            }
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("天气已设为 ").append(weather);
        }
        return ok(sb.toString()).toString();
    }

    private String getPlayerPosition(Player player) {
        if (player == null || !player.isOnline()) {
            return error("玩家不在线");
        }
        Location loc = player.getLocation();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("world", player.getWorld().getName());
        result.addProperty("x", loc.getBlockX());
        result.addProperty("y", loc.getBlockY());
        result.addProperty("z", loc.getBlockZ());
        result.addProperty("yaw", Math.round(loc.getYaw() * 10) / 10.0);
        result.addProperty("pitch", Math.round(loc.getPitch() * 10) / 10.0);
        return result.toString();
    }

    private String undo(Player player) {
        if (player == null) {
            return error("该工具需要玩家上下文");
        }
        BuildSession session = plugin.getSession(player.getUniqueId());
        if (session == null) {
            return error("当前没有进行中的建筑会话");
        }
        BlockHistory history = session.getHistory();
        if (history.size() == 0) {
            return error("没有可撤销的操作");
        }
        history.undoLast();
        return ok("已撤销上一次操作，剩余可撤销操作: " + history.size()).toString();
    }

    // ==================== 辅助方法 ====================

    private void recordHistory(Player player, World world, List<BlockHistory.Snapshot> changes) {
        if (changes.isEmpty() || player == null) {
            return;
        }
        BuildSession session = plugin.getSession(player.getUniqueId());
        if (session != null) {
            session.getHistory().record(world.getName(), changes);
        }
    }

    private static void snapshotIfChanged(List<BlockHistory.Snapshot> changes, Block block, BlockData newData) {
        if (!block.getBlockData().equals(newData)) {
            changes.add(new BlockHistory.Snapshot(block.getX(), block.getY(), block.getZ(),
                    block.getBlockData().getAsString()));
            block.setBlockData(newData, false);
        }
    }

    private static List<BlockHistory.Snapshot> snapshotRegion(World world,
                                                              int x1, int y1, int z1, int x2, int y2, int z2) {
        List<BlockHistory.Snapshot> snapshots = new ArrayList<>();
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.AIR) {
                        snapshots.add(new BlockHistory.Snapshot(x, y, z, block.getBlockData().getAsString()));
                    }
                }
            }
        }
        return snapshots;
    }

    /**
     * 解析方块参数为 BlockData。block 可直接携带方块状态（如 oak_stairs[facing=east]），
     * blockData 允许简写状态（如 facing=east，会自动补全）。
     */
    private static BlockData parseBlockData(String block, String blockData) {
        if (block.contains("[")) {
            return Bukkit.createBlockData(block);
        }
        Material material = parseMaterial(block);
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("未知方块类型: " + block);
        }
        if (blockData == null) {
            return material.createBlockData();
        }
        String combined = blockData.contains("[")
                ? blockData
                : material.getKey() + "[" + blockData + "]";
        return Bukkit.createBlockData(combined);
    }

    private String checkVolume(long volume) {
        long maxVolume = plugin.getConfig().getLong("safety.max-fill-volume", 32768);
        if (volume > maxVolume) {
            return error("操作体积 " + volume + " 超过上限 " + maxVolume + "，请缩小范围或分多次操作");
        }
        return null;
    }

    // ==================== 参数辅助 ====================

    private World resolveWorld(JsonObject a, Player player) {
        String worldName = getString(a, "world");
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return world;
            }
        }
        if (player != null) {
            return player.getWorld();
        }
        return Bukkit.getWorlds().get(0);
    }

    private static Integer getInt(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static Material parseMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            material = Material.matchMaterial(name.toUpperCase());
        }
        return material;
    }

    private static boolean checkHeight(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private static JsonObject ok(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("success", true);
        obj.addProperty("message", message);
        return obj;
    }

    private static String error(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("success", false);
        obj.addProperty("error", message);
        return obj.toString();
    }

    // ==================== 工具定义（JSON Schema） ====================

    private static JsonArray buildDefinitions() {
        JsonArray tools = new JsonArray();

        tools.add(tool("place_block", "在指定坐标放置一个方块", params(
                prop("x", "integer", "X 坐标", true),
                prop("y", "integer", "Y 坐标", true),
                prop("z", "integer", "Z 坐标", true),
                prop("block", "string", "方块类型，如 stone、oak_planks、minecraft:glass，"
                        + "也可以直接带方块状态如 oak_stairs[facing=east]", true),
                prop("block_data", "string", "可选，额外的方块状态字符串，如 facing=east", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("fill_blocks", "以两个对角坐标填充一个长方体区域的方块", params(
                prop("x1", "integer", "角点1 X 坐标", true),
                prop("y1", "integer", "角点1 Y 坐标", true),
                prop("z1", "integer", "角点1 Z 坐标", true),
                prop("x2", "integer", "角点2 X 坐标", true),
                prop("y2", "integer", "角点2 Y 坐标", true),
                prop("z2", "integer", "角点2 Z 坐标", true),
                prop("block", "string", "填充的方块类型，规则同 place_block", true),
                prop("block_data", "string", "可选，额外的方块状态字符串", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("get_block", "查询指定坐标的方块类型与方块状态", params(
                prop("x", "integer", "X 坐标", true),
                prop("y", "integer", "Y 坐标", true),
                prop("z", "integer", "Z 坐标", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("compare_block", "判断指定坐标处的方块是否是指定的方块类型", params(
                prop("x", "integer", "X 坐标", true),
                prop("y", "integer", "Y 坐标", true),
                prop("z", "integer", "Z 坐标", true),
                prop("block", "string", "要比较的方块类型，如 stone", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("get_block_nbt", "读取指定坐标方块实体的完整 NBT 数据（SNBT 格式）", params(
                prop("x", "integer", "X 坐标", true),
                prop("y", "integer", "Y 坐标", true),
                prop("z", "integer", "Z 坐标", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("set_block_nbt", "向指定坐标的方块实体写入 NBT 数据（SNBT 字符串）", params(
                prop("x", "integer", "X 坐标", true),
                prop("y", "integer", "Y 坐标", true),
                prop("z", "integer", "Z 坐标", true),
                prop("nbt", "string", "SNBT 格式的 NBT 字符串，如 {Text1:'{\"text\":\"你好\"}'}", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("run_command", "以控制台身份执行服务器命令，输出需通过 get_console_logs 查看", params(
                prop("command", "string", "要执行的服务器命令，不需要以 / 开头", true)
        )));

        tools.add(tool("get_console_logs", "查看服务器最近的控制台日志", params(
                prop("lines", "integer", "要查看的日志行数，默认 30", false)
        )));

        tools.add(tool("list_files", "列出插件配置文件夹中的所有文件（递归包含子文件夹）", params()));

        tools.add(tool("read_file", "读取插件配置文件夹中的一个文本文件", params(
                prop("path", "string", "相对于插件配置文件夹的路径，如 config.yml 或 schematics/house.txt", true),
                prop("lines", "integer", "可选，最多返回的行数，默认 500", false)
        )));

        tools.add(tool("replace_blocks", "将区域内匹配过滤条件的方块替换为指定方块，类似 WorldEdit 的 //replace", params(
                prop("x1", "integer", "角点1 X 坐标", true),
                prop("y1", "integer", "角点1 Y 坐标", true),
                prop("z1", "integer", "角点1 Z 坐标", true),
                prop("x2", "integer", "角点2 X 坐标", true),
                prop("y2", "integer", "角点2 Y 坐标", true),
                prop("z2", "integer", "角点2 Z 坐标", true),
                prop("block", "string", "替换成的方块类型，规则同 place_block", true),
                prop("filter", "string", "可选，只替换该类型的方块（如 stone）；不提供则替换所有非空气方块", false),
                prop("block_data", "string", "可选，额外的方块状态字符串", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("move_blocks", "将一个区域的方块整体平移到新位置（原位置清空）", params(
                prop("x1", "integer", "角点1 X 坐标", true),
                prop("y1", "integer", "角点1 Y 坐标", true),
                prop("z1", "integer", "角点1 Z 坐标", true),
                prop("x2", "integer", "角点2 X 坐标", true),
                prop("y2", "integer", "角点2 Y 坐标", true),
                prop("z2", "integer", "角点2 Z 坐标", true),
                prop("dx", "integer", "X 方向位移", true),
                prop("dy", "integer", "Y 方向位移", true),
                prop("dz", "integer", "Z 方向位移", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("copy_region", "复制一个区域到会话剪贴板，供 paste_region 使用", params(
                prop("x1", "integer", "角点1 X 坐标", true),
                prop("y1", "integer", "角点1 Y 坐标", true),
                prop("z1", "integer", "角点1 Z 坐标", true),
                prop("x2", "integer", "角点2 X 坐标", true),
                prop("y2", "integer", "角点2 Y 坐标", true),
                prop("z2", "integer", "角点2 Z 坐标", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("paste_region", "将剪贴板中的结构粘贴到指定坐标，支持旋转与镜像", params(
                prop("x", "integer", "粘贴目标点 X 坐标（结构最小角对齐该点）", true),
                prop("y", "integer", "粘贴目标点 Y 坐标", true),
                prop("z", "integer", "粘贴目标点 Z 坐标", true),
                prop("rotation", "string", "可选，旋转角度: none/cw90/cw180/ccw90，默认 none", false),
                prop("mirror", "string", "可选，镜像: none/left_right/front_back，默认 none", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("generate_shape", "生成几何体（球体/半球/圆柱/圆/金字塔/线条），适合穹顶、塔楼、屋顶等建筑元素", params(
                prop("shape", "string", "形状: sphere/hemisphere/cylinder/circle/pyramid/line", true),
                prop("block", "string", "填充的方块类型，规则同 place_block", true),
                prop("block_data", "string", "可选，额外的方块状态字符串", false),
                prop("cx", "integer", "中心点 X（line 形状使用 x1/x2 代替）", false),
                prop("cy", "integer", "中心点 Y", false),
                prop("cz", "integer", "中心点 Z", false),
                prop("radius", "integer", "半径（sphere/hemisphere/cylinder/circle）", false),
                prop("height", "integer", "高度（cylinder/circle/pyramid）", false),
                prop("base", "integer", "底边边长（pyramid）", false),
                prop("x1", "integer", "端点1 X（line）", false),
                prop("y1", "integer", "端点1 Y（line）", false),
                prop("z1", "integer", "端点1 Z（line）", false),
                prop("x2", "integer", "端点2 X（line）", false),
                prop("y2", "integer", "端点2 Y（line）", false),
                prop("z2", "integer", "端点2 Z（line）", false),
                prop("thickness", "integer", "线条粗细，默认 1（line）", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("get_region_summary", "统计一个区域内所有方块的类型与数量，用于检查建筑进度或了解已有结构", params(
                prop("x1", "integer", "角点1 X 坐标", true),
                prop("y1", "integer", "角点1 Y 坐标", true),
                prop("z1", "integer", "角点1 Z 坐标", true),
                prop("x2", "integer", "角点2 X 坐标", true),
                prop("y2", "integer", "角点2 Y 坐标", true),
                prop("z2", "integer", "角点2 Z 坐标", true),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("inspect_region", "查看一个区域内所有方块的完整布局，默认返回逐层 ASCII 平面图（含图例），也可用 list 格式输出逐方块清单", params(
                prop("x1", "integer", "角点1 X 坐标", true),
                prop("y1", "integer", "角点1 Y 坐标", true),
                prop("z1", "integer", "角点1 Z 坐标", true),
                prop("x2", "integer", "角点2 X 坐标", true),
                prop("y2", "integer", "角点2 Y 坐标", true),
                prop("z2", "integer", "角点2 Z 坐标", true),
                prop("format", "string", "可选，输出格式: map(逐层平面图,默认)/list(逐方块清单)", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("set_environment", "设置世界的时间与天气", params(
                prop("time", "integer", "可选，世界时间（0-24000，0 为清晨，6000 为正午）", false),
                prop("weather", "string", "可选，天气: clear/rain/thunder", false),
                prop("world", "string", "可选，世界名，默认使用玩家所在世界", false)
        )));

        tools.add(tool("get_player_position", "查询玩家当前的位置与朝向", params()));

        tools.add(tool("undo", "撤销上一次方块修改操作（可连续调用逐步撤销）", params()));

        return tools;
    }

    private static JsonObject tool(String name, String description, JsonObject parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    /**
     * @param properties 每个元素为 {"name","required","schema"}
     */
    private static JsonObject params(JsonObject... properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonObject property : properties) {
            String name = property.get("name").getAsString();
            boolean isRequired = property.get("required").getAsBoolean();
            props.add(name, property.getAsJsonObject("schema"));
            if (isRequired) {
                required.add(name);
            }
        }
        schema.add("properties", props);
        schema.add("required", required);
        return schema;
    }

    private static JsonObject prop(String name, String type, String description, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        schema.addProperty("description", description);

        JsonObject property = new JsonObject();
        property.addProperty("name", name);
        property.addProperty("required", required);
        property.add("schema", schema);
        return property;
    }
}
