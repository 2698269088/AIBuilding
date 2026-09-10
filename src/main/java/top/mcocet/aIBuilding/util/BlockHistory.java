package top.mcocet.aIBuilding.util;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 方块修改操作的历史记录，支持逐步撤销。
 * 每个条目记录一次操作中被修改方块的原始状态，撤销时按逆序恢复。
 */
public class BlockHistory {

    /**
     * 一个方块的还原快照（记录的是需要恢复成的数据，而非操作前的数据）。
     */
    public static class Snapshot {
        public final int x;
        public final int y;
        public final int z;
        public final String blockData;

        public Snapshot(int x, int y, int z, String blockData) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockData = blockData;
        }
    }

    private static class Entry {
        final String worldName;
        final List<Snapshot> snapshots;

        Entry(String worldName, List<Snapshot> snapshots) {
            this.worldName = worldName;
            this.snapshots = snapshots;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int maxEntries;

    public BlockHistory(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * 记录一次操作的还原快照。
     */
    public synchronized void record(String worldName, List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        entries.addLast(new Entry(worldName, new ArrayList<>(snapshots)));
        while (entries.size() > maxEntries) {
            entries.pollFirst();
        }
    }

    /**
     * 撤销最近一次操作。
     *
     * @return 是否有可撤销的操作
     */
    public synchronized boolean undoLast() {
        Entry entry = entries.pollLast();
        if (entry == null) {
            return false;
        }
        World world = Bukkit.getWorld(entry.worldName);
        if (world == null) {
            return true;
        }
        // 逆序恢复，保证同一方块被多次记录时最终状态正确
        List<Snapshot> snapshots = entry.snapshots;
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            Snapshot snapshot = snapshots.get(i);
            world.getBlockAt(snapshot.x, snapshot.y, snapshot.z)
                    .setBlockData(Bukkit.createBlockData(snapshot.blockData), false);
        }
        return true;
    }

    public synchronized int size() {
        return entries.size();
    }
}
