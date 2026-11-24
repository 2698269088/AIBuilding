package top.mcocet.aIBuilding.util;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import de.tr7zw.changeme.nbtapi.utils.MinecraftVersion;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/**
 * 通过 NBT-API 读写方块 NBT 数据。
 * NBT-API 会根据服务端版本自行选择反射目标，因此不再绑定特定的 NMS 版本。
 */
public final class NbtUtil {

    private static boolean available;

    private NbtUtil() {
    }

    /**
     * 插件启用时调用，初始化 NBT-API 并记录可用性。
     */
    public static boolean init() {
        // 避免把本插件注册为 NBT-API 的 bStats 统计来源
        MinecraftVersion.disableBStats();
        available = NBT.preloadApi();
        return available;
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * 读取方块实体的完整 NBT，返回 SNBT 字符串。
     * 非方块实体返回 null。
     */
    public static String getBlockNbt(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof TileState)) {
            return null;
        }
        return NBT.get(state, ReadableNBT::toString);
    }

    /**
     * 将 SNBT 字符串合并写入方块实体，SNBT 中未出现的键保持不变。
     *
     * @return 方块不存在方块实体时返回 false
     */
    public static boolean setBlockNbt(Block block, String snbt) {
        BlockState state = block.getState();
        if (!(state instanceof TileState)) {
            return false;
        }
        ReadWriteNBT parsed = NBT.parseNBT(snbt);
        NBT.modify(state, nbt -> {
            nbt.mergeCompound(parsed);
        });
        return true;
    }

    /**
     * 校验并解析 SNBT，供需要写入多次的场景复用同一份数据。
     */
    public static ReadWriteNBT parseSnbt(String snbt) {
        return NBT.parseNBT(snbt);
    }

    /**
     * 将已解析的 NBT 合并写入实体。
     */
    public static void setEntityNbt(Entity entity, ReadWriteNBT nbt) {
        NBT.modify(entity, data -> {
            data.mergeCompound(nbt);
        });
    }

    /**
     * 将已解析的 NBT 合并写入物品。
     */
    public static void setItemNbt(ItemStack item, ReadWriteNBT nbt) {
        NBT.modify(item, data -> {
            data.mergeCompound(nbt);
        });
    }
}
