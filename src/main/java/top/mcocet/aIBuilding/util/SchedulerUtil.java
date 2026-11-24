package top.mcocet.aIBuilding.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 调度兼容层：Folia 走区域 / 全局 / 异步 / 实体调度器，Paper / Spigot 退回 BukkitScheduler。
 * Folia 的调度 API 在 1.18.2 的 paper-api 中不存在，因此全部通过反射调用。
 */
public final class SchedulerUtil {

    private static final boolean FOLIA;

    private static Object globalScheduler;
    private static Object regionScheduler;
    private static Object asyncScheduler;
    private static Method globalExecuteMethod;
    private static Method globalRunDelayedMethod;
    private static Method regionExecuteMethod;
    private static Method asyncRunNowMethod;
    private static Method entityExecuteMethod;
    private static Method entityGetSchedulerMethod;
    private static Method scheduledTaskCancelMethod;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (Throwable ignored) {
            folia = false;
        }
        FOLIA = folia;
        if (folia) {
            try {
                Object server = Bukkit.getServer();
                globalScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
                regionScheduler = server.getClass().getMethod("getRegionScheduler").invoke(server);
                asyncScheduler = server.getClass().getMethod("getAsyncScheduler").invoke(server);

                scheduledTaskCancelMethod = Class.forName(
                        "io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");

                globalExecuteMethod = globalScheduler.getClass()
                        .getMethod("execute", Plugin.class, Runnable.class);
                globalRunDelayedMethod = globalScheduler.getClass()
                        .getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                regionExecuteMethod = regionScheduler.getClass()
                        .getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
                asyncRunNowMethod = asyncScheduler.getClass()
                        .getMethod("runNow", Plugin.class, Consumer.class);

                entityGetSchedulerMethod = Entity.class.getMethod("getScheduler");
                entityExecuteMethod = entityGetSchedulerMethod.getReturnType()
                        .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[AIBuilding] Folia 调度器初始化失败，将退回 Bukkit 调度器: " + t);
            }
        }
    }

    private SchedulerUtil() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * 是否已成功拿到 Folia 的调度器。Folia 上返回 false 说明插件无法安全调度任务。
     */
    public static boolean isSupported() {
        return !FOLIA || globalExecuteMethod != null;
    }

    /**
     * 在全局线程执行（Folia）或主线程执行（Paper / Spigot）。
     */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (!FOLIA || globalExecuteMethod == null) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invoke(globalExecuteMethod, globalScheduler, plugin, task);
    }

    /**
     * 在目标方块所在区域的线程执行（Folia），Paper / Spigot 上等同主线程。
     */
    public static void runAt(Plugin plugin, World world, int blockX, int blockZ, Runnable task) {
        if (!FOLIA || regionExecuteMethod == null) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invoke(regionExecuteMethod, regionScheduler, plugin, world, blockX >> 4, blockZ >> 4, task);
    }

    /**
     * 在玩家 / 实体所属区域的线程执行，实体跨区域时跟随实体。
     */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (!FOLIA || entityExecuteMethod == null || entityGetSchedulerMethod == null) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            Object scheduler = entityGetSchedulerMethod.invoke(entity);
            entityExecuteMethod.invoke(scheduler, plugin, task, null, 0L);
        } catch (Throwable t) {
            runGlobal(plugin, task);
        }
    }

    /**
     * 异步执行。
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (!FOLIA || asyncRunNowMethod == null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        invoke(asyncRunNowMethod, asyncScheduler, plugin, (Consumer<Object>) scheduled -> task.run());
    }

    /**
     * 延迟执行（单位 tick），返回可用于取消的句柄。
     */
    public static Runnable runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        if (!FOLIA || globalRunDelayedMethod == null) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return bukkitTask::cancel;
        }
        try {
            Object scheduled = globalRunDelayedMethod.invoke(globalScheduler, plugin,
                    (Consumer<Object>) task1 -> task.run(), delayTicks);
            return () -> {
                try {
                    scheduledTaskCancelMethod.invoke(scheduled);
                } catch (Throwable ignored) {
                }
            };
        } catch (Throwable t) {
            task.run();
            return () -> {
            };
        }
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (Throwable t) {
            throw new IllegalStateException("调度任务失败: " + t.getMessage(), t);
        }
    }
}
