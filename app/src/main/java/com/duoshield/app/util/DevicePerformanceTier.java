package com.duoshield.app.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

/**
 * Capability-based device performance tier.
 *
 * <p>This replaces the old {@code Build.SUPPORTED_64_BIT_ABIS} check that used to drive video
 * capture resolution, bitrate policy and the thermal watchdog. That check was wrong: it assumed
 * "64-bit == fast", so an arm64 budget SoC such as the MediaTek Helio P35 (MT6765, 8x Cortex-A53
 * @ 2.2/1.6 GHz, PowerVR GE8320) was treated as a flagship and handed 720p30 capture with
 * unbounded bitrate and no thermal protection.
 *
 * <p>The decisive signal here is CPU <em>microarchitecture</em>, not core count or clock speed.
 * Cortex-A53/A55-class cores are in-order designs: 8x A53 @ 2.2 GHz has a small fraction of the
 * per-clock throughput of any out-of-order core, so MHz and core count are actively misleading.
 * If every core in the system is an in-order "little" core, the device is {@link #LOW} no matter
 * how many of them there are or how high they clock.
 *
 * <p>Resolution is performed once and cached. All probing is a handful of small sysfs/procfs
 * reads done lazily on first call, never on a hot path.
 */
public enum DevicePerformanceTier {

    /**
     * In-order CPU cores (A53/A55-class), low RAM, or a 32-bit-only ABI. Budget hardware that
     * cannot sustain high resolution encode/decode: MediaTek Helio A/P/G series, Snapdragon
     * 2xx/4xx, Unisoc SC98xx, Exynos 78xx-class.
     */
    LOW,

    /** Mid-range hardware, or a device we could not confidently identify. Conservative default. */
    MID,

    /** Out-of-order CPU cores and ample RAM. Full quality, unbounded bandwidth estimation. */
    HIGH;

    private static final String TAG = "DevicePerfTier";

    /**
     * ARM implementer part IDs for in-order / "little" cores. A CPU built entirely from these
     * cannot sustain high-resolution real-time video encode regardless of clock or core count.
     */
    private static final Set<Integer> IN_ORDER_PARTS = new HashSet<>();

    static {
        IN_ORDER_PARTS.add(0xc07); // Cortex-A7
        IN_ORDER_PARTS.add(0xc08); // Cortex-A8
        IN_ORDER_PARTS.add(0xd01); // Cortex-A32
        IN_ORDER_PARTS.add(0xd03); // Cortex-A53  <- Helio P35 / G36, Snapdragon 4xx
        IN_ORDER_PARTS.add(0xd04); // Cortex-A35
        IN_ORDER_PARTS.add(0xd05); // Cortex-A55
        IN_ORDER_PARTS.add(0xd46); // Cortex-A510
        IN_ORDER_PARTS.add(0xd80); // Cortex-A520
    }

    /** Devices reporting less than this are always LOW. Nominal 3 GB reports ~2.8 GB. */
    private static final long LOW_RAM_BYTES = 3L * 1024L * 1024L * 1024L;

    /** Devices reporting less than this are capped at MID. Nominal 6 GB reports ~5.6 GB. */
    private static final long MID_RAM_BYTES = 5600L * 1024L * 1024L;

    private static final int LOW_MEMORY_CLASS_MB = 128;

    /** Weak fallback only, used when {@code /proc/cpuinfo} does not expose part IDs. */
    private static final long SLOW_CLOCK_KHZ = 2_000_000L;

    private static volatile DevicePerformanceTier cached;

    /**
     * Resolves the static tier for this device, caching the result. Safe to call from any thread
     * and from a hot path once warmed.
     */
    public static DevicePerformanceTier get(Context context) {
        DevicePerformanceTier local = cached;
        if (local != null) {
            return local;
        }
        synchronized (DevicePerformanceTier.class) {
            if (cached == null) {
                cached = resolve(context);
            }
            return cached;
        }
    }

    /**
     * Returns the already-resolved tier, or {@link #MID} if resolution has not happened yet.
     * For call sites that have no {@link Context} handy; prefer {@link #get(Context)}.
     */
    public static DevicePerformanceTier getCachedOrDefault() {
        DevicePerformanceTier local = cached;
        return local != null ? local : MID;
    }

    private static DevicePerformanceTier resolve(Context context) {
        // 1. Never regress the previous behaviour: a 32-bit-only device is always LOW.
        boolean is32BitOnly =
                Build.SUPPORTED_64_BIT_ABIS == null || Build.SUPPORTED_64_BIT_ABIS.length == 0;

        // 2. CPU microarchitecture: the decisive signal.
        CpuInfo cpu = readCpuInfo();

        // 3. Memory signals.
        boolean lowRamFlagged = false;
        int memoryClass = -1;
        long totalMem = -1L;
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                lowRamFlagged = am.isLowRamDevice();
                memoryClass = am.getMemoryClass();
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                totalMem = info.totalMem;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read memory info (" + com.duoshield.app.util.LogRedact.error(t) + ")");
        }

        DevicePerformanceTier tier;
        String reason;

        if (is32BitOnly) {
            tier = LOW;
            reason = "32-bit-only ABI";
        } else if (cpu.allCoresInOrder) {
            // The Helio P35 case: arm64, 8 cores, 2.2 GHz, but every core is an in-order A53.
            tier = LOW;
            reason = "all " + cpu.coresDescribed + " cores are in-order/little";
        } else if (lowRamFlagged) {
            tier = LOW;
            reason = "isLowRamDevice";
        } else if (memoryClass > 0 && memoryClass <= LOW_MEMORY_CLASS_MB) {
            tier = LOW;
            reason = "memoryClass=" + memoryClass + "MB";
        } else if (totalMem > 0 && totalMem < LOW_RAM_BYTES) {
            tier = LOW;
            reason = "totalMem=" + (totalMem / (1024 * 1024)) + "MB";
        } else if (cpu.hasOutOfOrderCore && (totalMem <= 0 || totalMem >= MID_RAM_BYTES)) {
            tier = HIGH;
            reason = "out-of-order cores + "
                    + (totalMem > 0 ? (totalMem / (1024 * 1024)) + "MB RAM" : "unknown RAM");
        } else if (!cpu.partsComplete && cpu.maxClockKhz > 0 && cpu.maxClockKhz < SLOW_CLOCK_KHZ) {
            // cpuinfo was masked (some OEMs strip "CPU part" on Android 12+) and the clock is low.
            tier = LOW;
            reason = "masked cpuinfo, maxClock=" + (cpu.maxClockKhz / 1000) + "MHz";
        } else {
            // Unidentified, or out-of-order cores with modest RAM. Stay conservative.
            tier = MID;
            reason = "unclassified (" + cpu.coresDescribed + ", "
                    + (totalMem > 0 ? (totalMem / (1024 * 1024)) + "MB RAM" : "unknown RAM") + ")";
        }

        Log.i(TAG, "Resolved tier=" + tier + " because " + reason
                + " [soc=" + socModel() + " hardware=" + Build.HARDWARE
                + " model=" + Build.MODEL + "]");
        return tier;
    }

    /** {@code Build.SOC_MODEL} is API 31+; fall back to the board name below that. */
    private static String socModel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Build.SOC_MODEL;
        }
        return Build.BOARD;
    }

    // ---------------------------------------------------------------------------------------
    // Thermal
    // ---------------------------------------------------------------------------------------

    /**
     * Current thermal status, or {@link PowerManager#THERMAL_STATUS_NONE} when unavailable
     * (the API requires API 29; this project targets minSdk 26).
     */
    public static int currentThermalStatus(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return PowerManager.THERMAL_STATUS_NONE;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.getCurrentThermalStatus();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read thermal status (" + com.duoshield.app.util.LogRedact.error(t) + ")");
        }
        return PowerManager.THERMAL_STATUS_NONE;
    }

    /** True when the device is throttling at MODERATE or worse. */
    public static boolean isThrottling(int thermalStatus) {
        return thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE;
    }

    /**
     * The static tier demoted one step while the device is throttling, so a hot HIGH device
     * behaves like a MID one and a hot MID device behaves like a LOW one.
     */
    public static DevicePerformanceTier effectiveTier(Context context) {
        DevicePerformanceTier base = get(context);
        return isThrottling(currentThermalStatus(context)) ? base.demoted() : base;
    }

    /** One step down the ladder; {@link #LOW} is the floor. */
    public DevicePerformanceTier demoted() {
        switch (this) {
            case HIGH:
                return MID;
            case MID:
            case LOW:
            default:
                return LOW;
        }
    }

    public boolean isAtMost(DevicePerformanceTier other) {
        return ordinal() <= other.ordinal();
    }

    /**
     * Maximum messages bound into a chat list during its initial render.
     *
     * <p>Raised from 120/300 to 250/500. A window smaller than the user's scroll history means
     * scrolling up crosses the window edge and triggers a fresh load-and-decrypt batch, which
     * is another place the "[Decrypting...]" placeholder surfaced on already-read messages.
     */
    public int initialChatWindow() {
        return this == LOW ? 250 : 500;
    }

    /**
     * RecyclerView holders retained beyond the visible children.
     *
     * <p>Raised from 4/10 to 12/16. This is the other half of the "[Decrypting...]" bug: a
     * holder evicted from the view cache loses its decrypted body, so scrolling back up
     * re-binds the row and re-queues the decrypt from scratch. At a cache of 4 on LOW, a
     * short flick past five rows was enough to evict and re-request work that had already
     * completed, so the placeholder reappeared on content the user had already read. Retained
     * holders are small; re-decrypting is not.
     */
    public int recyclerViewCacheSize() {
        return this == LOW ? 12 : 16;
    }

    /**
     * Maximum simultaneous media preparation/upload operations.
     *
     * <p>Raised from 1/3 to 2/4 so a single large attachment no longer serializes every other
     * pending media operation behind it.
     */
    public int mediaConcurrency() {
        return this == LOW ? 2 : 4;
    }

    /**
     * Long edge used for list-row image requests.
     *
     * <p>Raised from 720/1280 to 1080/1600. This is the clearest size-for-quality trade: the
     * 720px long edge was visibly soft when a list image was opened full-screen on a 1080p
     * display, because the cached decode had already discarded the detail.
     */
    public int listImageEdgePx() {
        return this == LOW ? 1080 : 1600;
    }

    /**
     * Bound for shared short-lived CPU work. Calls use their own real-time threads.
     *
     * <p>The LOW arm was 1, which made the shared CPU pool effectively a single-threaded
     * queue: one slow job (a large attachment decrypt, a thumbnail decode) blocked every
     * other CPU job behind it. LOW-tier devices in this codebase are still 8-core parts (the
     * Helio P35 is 8x A53) — they are classified LOW for being in-order, not for lacking
     * cores — so allowing 2 workers is well within budget and lets a slow job be overtaken.
     */
    public int shortCpuWorkerCount() {
        return this == LOW
                ? Math.max(2, Math.min(3, Runtime.getRuntime().availableProcessors() / 2))
                : Math.max(3, Math.min(6, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Bound for shared blocking I/O work.
     *
     * <p>Raised from 2/4 to 3/6. These threads are usually parked on disk or network, so they
     * cost scheduler slots rather than sustained CPU, and a deeper pool keeps a slow network
     * fetch from blocking local database reads.
     */
    public int shortIoWorkerCount() {
        return this == LOW ? 3 : 6;
    }

    /**
     * Maximum queued short jobs before producers apply backpressure.
     *
     * <p>Raised from 32/96 to 128/256. The old LOW ceiling of 32 was a hard backpressure wall:
     * opening a busy chat can enqueue a decrypt job per row, and once the queue saturated the
     * submitting thread either blocked or the job was rejected, stalling every row behind it.
     * These jobs are small closures, so a deeper queue costs very little memory and removes an
     * entire class of burst-induced stalls.
     */
    public int shortJobQueueCapacity() {
        return this == LOW ? 128 : 256;
    }

    /**
     * Initial adjacent-item prefetch count for linear message lists.
     *
     * <p>Raised from 2/8 back toward the pre-tiering flat 12. Prefetch is what binds rows
     * <em>before</em> they scroll into view, which is exactly when the asynchronous decryption
     * for that row gets queued. At 2 on LOW the decrypt request was issued barely one row
     * ahead of the user's eye, so the "[Decrypting...]" placeholder was routinely still on
     * screen when the row became visible. A deeper prefetch buys the decrypt executor a head
     * start; the cost is a handful of extra view binds, which is cheap next to the visible
     * placeholder it removes.
     */
    public int recyclerViewPrefetchCount() {
        return this == LOW ? 8 : 12;
    }

    /**
     * Maximum preview thumbnails kept active at once by album/send UIs.
     *
     * <p>Raised from 6/16 to 12/24 so a picker grid does not thrash thumbnails in and out on
     * a single screenful of scrolling.
     */
    public int mediaPreviewCount() {
        return this == LOW ? 12 : 24;
    }

    /** Maximum concurrent link-preview requests and retained entries. */
    public int linkPreviewConcurrency() {
        return this == LOW ? 1 : 3;
    }

    public int linkPreviewCacheEntries() {
        return this == LOW ? 24 : 80;
    }

    /** Bitmap and decoded-resource budgets consumed by the Glide module. */
    public long glideBitmapPoolBytes() {
        return (this == LOW ? 8L : 32L) * 1024L * 1024L;
    }

    public long glideMemoryCacheBytes() {
        return (this == LOW ? 6L : 16L) * 1024L * 1024L;
    }

    public long glideDiskCacheBytes() {
        return (this == LOW ? 50L : 150L) * 1024L * 1024L;
    }

    /** Firestore's persistent cache budget. */
    public long firestoreCacheBytes() {
        return (this == LOW ? 32L : 100L) * 1024L * 1024L;
    }

    // ---------------------------------------------------------------------------------------
    // CPU probing
    // ---------------------------------------------------------------------------------------

    private static final class CpuInfo {
        boolean allCoresInOrder;
        boolean hasOutOfOrderCore;
        /** True when we saw a part ID for every CPU the kernel exposes. */
        boolean partsComplete;
        long maxClockKhz;
        String coresDescribed = "unknown CPU";
    }

    private static CpuInfo readCpuInfo() {
        CpuInfo info = new CpuInfo();
        int sysfsCoreCount = countSysfsCores();
        info.maxClockKhz = readMaxClockKhz(sysfsCoreCount);

        int partsSeen = 0;
        int inOrderSeen = 0;
        Set<Integer> distinctParts = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0 || !line.regionMatches(true, 0, "CPU part", 0, 8)) {
                    continue;
                }
                String value = line.substring(colon + 1).trim();
                try {
                    int part = Integer.decode(value);
                    partsSeen++;
                    distinctParts.add(part);
                    if (IN_ORDER_PARTS.contains(part)) {
                        inOrderSeen++;
                    }
                } catch (NumberFormatException ignored) {
                    // Unparseable part ID; ignore this line.
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read /proc/cpuinfo", t);
        }

        if (partsSeen > 0) {
            // Some kernels only list *online* cores. If big cores happen to be parked we would
            // see only little cores and wrongly conclude LOW, so only trust an "all little"
            // verdict when we saw a part for every CPU the kernel exposes in sysfs.
            info.partsComplete = sysfsCoreCount <= 0 || partsSeen >= sysfsCoreCount;
            info.hasOutOfOrderCore = inOrderSeen < partsSeen;
            info.allCoresInOrder = info.partsComplete && inOrderSeen == partsSeen;
            info.coresDescribed = partsSeen + "x ARM part" + formatParts(distinctParts);
        } else if (sysfsCoreCount > 0) {
            info.coresDescribed = sysfsCoreCount + " cores, no part IDs";
        }
        return info;
    }

    private static String formatParts(Set<Integer> parts) {
        StringBuilder sb = new StringBuilder();
        for (Integer part : parts) {
            sb.append(sb.length() == 0 ? " " : "/").append("0x").append(Integer.toHexString(part));
        }
        return sb.toString();
    }

    private static int countSysfsCores() {
        try {
            File[] cpus = new File("/sys/devices/system/cpu").listFiles(
                    (dir, name) -> name.matches("cpu[0-9]+"));
            if (cpus != null) {
                return cpus.length;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to enumerate CPUs", t);
        }
        return Runtime.getRuntime().availableProcessors();
    }

    private static long readMaxClockKhz(int coreCount) {
        long max = 0L;
        for (int i = 0; i < coreCount; i++) {
            String path = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq";
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line = reader.readLine();
                if (line != null) {
                    max = Math.max(max, Long.parseLong(line.trim()));
                }
            } catch (Throwable ignored) {
                // Core offline or cpufreq unavailable; skip.
            }
        }
        return max;
    }
}
