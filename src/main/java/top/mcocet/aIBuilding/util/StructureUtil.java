package top.mcocet.aIBuilding.util;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Random;

/**
 * 通过反射调用 Paper 1.18.2（Mojang-Mapped）的 StructureTemplate，
 * 实现区域复制/粘贴（支持旋转与镜像），效果等同结构方块。
 */
public final class StructureUtil {

    /**
     * 已复制的区域结构。
     */
    public static class CopiedStructure {
        public final Object template;
        public final int sizeX;
        public final int sizeY;
        public final int sizeZ;

        CopiedStructure(Object template, int sizeX, int sizeY, int sizeZ) {
            this.template = template;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }
    }

    private static final boolean AVAILABLE;
    private static Method craftWorldGetHandle;
    private static Constructor<?> blockPosConstructor;
    private static Constructor<?> templateConstructor;
    private static Method templateSave;
    private static Method templatePlace;
    private static Constructor<?> settingsConstructor;
    private static Method settingsSetRotation;
    private static Method settingsSetMirror;
    private static Method settingsSetIgnoreEntities;
    private static Class<?> rotationEnum;
    private static Class<?> mirrorEnum;

    static {
        boolean ok;
        try {
            String serverPackage = Bukkit.getServer().getClass().getPackage().getName();
            String version = serverPackage.substring(serverPackage.lastIndexOf('.') + 1);
            Class<?> craftWorldClass = Class.forName("org.bukkit.craftbukkit." + version + ".CraftWorld");
            craftWorldGetHandle = craftWorldClass.getMethod("getHandle");

            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);

            Class<?> serverLevelAccessorClass = Class.forName("net.minecraft.world.level.ServerLevelAccessor");

            Class<?> templateClass =
                    Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate");
            templateConstructor = templateClass.getConstructor();
            templateSave = templateClass.getMethod("save", serverLevelAccessorClass,
                    blockPosClass, blockPosClass, boolean.class);
            Class<?> settingsClass =
                    Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings");
            templatePlace = templateClass.getMethod("place", serverLevelAccessorClass,
                    blockPosClass, blockPosClass, settingsClass, Random.class, int.class);

            settingsConstructor = settingsClass.getConstructor();
            rotationEnum = Class.forName("net.minecraft.world.level.block.Rotation");
            mirrorEnum = Class.forName("net.minecraft.world.level.block.Mirror");
            settingsSetRotation = settingsClass.getMethod("setRotation", rotationEnum);
            settingsSetMirror = settingsClass.getMethod("setMirror", mirrorEnum);
            settingsSetIgnoreEntities = settingsClass.getMethod("setIgnoreEntities", boolean.class);

            ok = true;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[AIBuilding] StructureTemplate 反射初始化失败，复制/粘贴工具不可用: " + t.getMessage());
            ok = false;
        }
        AVAILABLE = ok;
    }

    private StructureUtil() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * 复制两个对角坐标围成的区域。
     *
     * @return 复制成功返回结构对象，失败返回 null
     */
    public static CopiedStructure copyRegion(World world,
                                             int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        Object nmsWorld = craftWorldGetHandle.invoke(world);
        Object pos1 = blockPosConstructor.newInstance(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
        Object pos2 = blockPosConstructor.newInstance(
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        Object template = templateConstructor.newInstance();
        boolean success = (Boolean) templateSave.invoke(template, nmsWorld, pos1, pos2, false);
        if (!success) {
            return null;
        }
        return new CopiedStructure(template,
                Math.abs(x2 - x1) + 1, Math.abs(y2 - y1) + 1, Math.abs(z2 - z1) + 1);
    }

    /**
     * 将复制的结构粘贴到目标坐标（结构最小角对齐该坐标）。
     *
     * @param rotation none / cw90 / cw180 / ccw90
     * @param mirror   none / left_right / front_back
     */
    public static boolean paste(World world, CopiedStructure structure, int x, int y, int z,
                                String rotation, String mirror) throws Exception {
        Object nmsWorld = craftWorldGetHandle.invoke(world);
        Object pos = blockPosConstructor.newInstance(x, y, z);

        Object settings = settingsConstructor.newInstance();
        settingsSetIgnoreEntities.invoke(settings, true);
        String rot = rotation == null ? "none" : rotation.toLowerCase();
        if (!rot.equals("none")) {
            settingsSetRotation.invoke(settings, enumConstant(rotationEnum, rotationConstant(rot)));
        }
        String mir = mirror == null ? "none" : mirror.toLowerCase();
        if (!mir.equals("none")) {
            settingsSetMirror.invoke(settings, enumConstant(mirrorEnum, mirrorConstant(mir)));
        }

        // flags=3: 更新邻居与客户端
        return (Boolean) templatePlace.invoke(structure.template, nmsWorld, pos, pos, settings, new Random(), 3);
    }

    private static String rotationConstant(String rotation) {
        switch (rotation) {
            case "cw90":
            case "clockwise_90":
                return "CLOCKWISE_90";
            case "cw180":
            case "clockwise_180":
                return "CLOCKWISE_180";
            case "ccw90":
            case "counterclockwise_90":
                return "COUNTERCLOCKWISE_90";
            default:
                return "NONE";
        }
    }

    private static String mirrorConstant(String mirror) {
        switch (mirror) {
            case "left_right":
                return "LEFT_RIGHT";
            case "front_back":
                return "FRONT_BACK";
            default:
                return "NONE";
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass.asSubclass(Enum.class), name);
    }
}
