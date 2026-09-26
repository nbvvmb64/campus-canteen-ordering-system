package edu.hitsz.canteen.persistence;

import edu.hitsz.canteen.exception.BusinessException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Cross-process cutover gate for the original project data directory. */
public final class LegacyWriteGate {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private static final String MARKER = ".web-mode";
    private static final String LOCK_FILE = ".business-cutover.lock";

    private LegacyWriteGate() { }

    public static boolean isOfficialData(Path path) {
        Path dir = path.toAbsolutePath().normalize();
        Path root = dir.getParent();
        return root != null && "data".equals(dir.getFileName().toString())
                && Files.isRegularFile(root.resolve("README.md"))
                && Files.isRegularFile(root.resolve("docs/ARCHITECTURE.md"));
    }

    public static boolean isCutOver(Path path) {
        return isOfficialData(path) && Files.exists(path.toAbsolutePath().normalize().resolve(MARKER));
    }

    public static void rejectIfCutOver(Path path) {
        if (isCutOver(path)) {
            throw new BusinessException("网页模式已启用或正在迁移；终端版禁止写入正式CSV，请使用网页端");
        }
    }

    public static <T> T officialWrite(Path path, Supplier<T> work) {
        if (!isOfficialData(path)) return work.get();
        return withCutoverLock(path, () -> {
            rejectIfCutOver(path);
            return work.get();
        });
    }

    public static <T> T withCutoverLock(Path path, Supplier<T> work) {
        Path dir = path.toAbsolutePath().normalize();
        if (!isOfficialData(dir)) throw new BusinessException("迁移锁仅允许用于正式data目录");
        ReentrantLock local = LOCAL_LOCKS.computeIfAbsent(dir, unused -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = local.tryLock(10, TimeUnit.SECONDS);
            if (!acquired) throw new BusinessException("正式数据写入锁超时");
            try (FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (true) {
                    try {
                        FileLock lock = channel.tryLock();
                        if (lock != null) {
                            try (lock) { return work.get(); }
                        }
                    } catch (OverlappingFileLockException ignored) {
                        // A second database instance in this JVM has the lock.
                    }
                    if (System.nanoTime() >= deadline) throw new BusinessException("正式数据跨进程锁超时");
                    Thread.sleep(50);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待正式数据锁时被中断", e);
        } catch (IOException e) {
            throw new BusinessException("无法取得正式数据锁，已拒绝写入", e);
        } finally {
            if (acquired) local.unlock();
        }
    }
}
