package top.mcocet.aIBuilding.util;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 通过反射读写方块 NBT 数据。
 * 目标运行环境为 Paper 1.18.2（Mojang-Mapped），因此直接反射
 * net.minecraft 包下的 BlockEntity 相关类，避免引入 NMS 编译依赖。
 */
public final class NbtUtil {

    private static final boolean AVAILABLE;
    private static Method craftWorldGetHandle;
    private static Constructor<?> blockPosConstructor;
    private static Method serverLevelGetBlockEntity;
    private static Method blockEntitySaveWithFullMetadata;
    private static Method blockEntityLoad;
    private static Method blockEntitySetChanged;
    private static Method tagParserParseTag;

    static {
        boolean ok;
        try {
            // org.bukkit.craftbukkit.v1_18_R2.CraftWorld
            String serverPackage = Bukkit.getServer().getClass().getPackage().getName();
            String version = serverPackage.substring(serverPackage.lastIndexOf('.') + 1);
            Class<?> craftWorldClass = Class.forName("org.bukkit.craftbukkit." + version + ".CraftWorld");
            craftWorldGetHandle = craftWorldClass.getMethod("getHandle");

            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);

            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            serverLevelGetBlockEntity = serverLevelClass.getMethod("getBlockEntity", blockPosClass);

            Class<?> blockEntityClass = Class.forName("net.minecraft.world.level.block.entity.BlockEntity");
            blockEntitySaveWithFullMetadata = blockEntityClass.getMethod("saveWithFullMetadata");
            blockEntityLoad = blockEntityClass.getMethod("load",
                    Class.forName("net.minecraft.nbt.CompoundTag"));
            blockEntitySetChanged = blockEntityClass.getMethod("setChanged");

            Class<?> tagParserClass = Class.forName("net.minecraft.nbt.TagParser");
            tagParserParseTag = tagParserClass.getMethod("parseTag", String.class);

            ok = true;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[AIBuilding] NBT 反射初始化失败，NBT 工具不可用: " + t.getMessage());
            ok = false;
        }
        AVAILABLE = ok;
    }

    private NbtUtil() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * 读取方块实体的完整 NBT，返回 SNBT 字符串。
     * 非方块实体返回 null。
     */
    public static String getBlockNbt(Block block) throws Exception {
        Object blockEntity = getBlockEntity(block);
        if (blockEntity == null) {
            return null;
        }
        Object tag = blockEntitySaveWithFullMetadata.invoke(blockEntity);
        return tag.toString();
    }

    /**
     * 将 SNBT 字符串写入方块实体。
     *
     * @return 方块不存在方块实体时返回 false
     */
    public static boolean setBlockNbt(Block block, String snbt) throws Exception {
        Object blockEntity = getBlockEntity(block);
        if (blockEntity == null) {
            return false;
        }
        Object tag = tagParserParseTag.invoke(null, snbt);
        blockEntityLoad.invoke(blockEntity, tag);
        blockEntitySetChanged.invoke(blockEntity);
        return true;
    }

    private static Object getBlockEntity(Block block) throws Exception {
        Object nmsWorld = craftWorldGetHandle.invoke(block.getWorld());
        Object pos = blockPosConstructor.newInstance(block.getX(), block.getY(), block.getZ());
        return serverLevelGetBlockEntity.invoke(nmsWorld, pos);
    }
}
