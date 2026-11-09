/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.metrics;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Collector;
import dev.warp.api.metrics.Histogram;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.metrics.SampleVisitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.UnixOperatingSystemMXBean;

/**
 * The JVM and the process, read from JMX at scrape time with the names the Prometheus Java clients
 * use, so that the standard dashboards work. CPU and memory limits come from the JDK's container
 * support: in a container they are the cgroup's, not the host's.
 *
 * <p>GC pauses are a real histogram, fed by the collectors' notifications ({@link #install}).
 */
public final class JvmCollector implements Collector {

  private static final List<String> AREA_POOL = List.of("area", "pool");
  private static final List<String> GC = List.of("gc");
  private static final List<String> NONE = List.of();

  private final List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
  private final List<GarbageCollectorMXBean> collectors =
      ManagementFactory.getGarbageCollectorMXBeans();
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
  private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
  private final java.lang.management.OperatingSystemMXBean os =
      ManagementFactory.getOperatingSystemMXBean();

  private JvmCollector() {}

  /**
   * Registers the JVM collector and the GC pause histogram.
   *
   * @param metrics the registry
   */
  public static void install(Metrics metrics) {
    metrics.collector("jvm", new JvmCollector());
    Histogram pauses =
        metrics.histogram(
            "jvm_gc_pause_seconds",
            "Stop-the-world pauses of the garbage collector, by collector.",
            Buckets.FAST,
            "gc");
    for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (collector instanceof NotificationEmitter emitter) {
        Histogram.Child child = pauses.labels(collector.getName());
        emitter.addNotificationListener(
            (notification, _) -> {
              if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(
                  notification.getType())) {
                GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from(
                        (CompositeData) notification.getUserData());
                child.observe(info.getGcInfo().getDuration() / 1000.0);
              }
            },
            null,
            null);
      }
    }
  }

  @Override
  public void collect(SampleVisitor out) {
    memory(out);
    gc(out);
    out.family("jvm_threads_live", MetricType.GAUGE, "Live threads, daemon ones included.");
    out.sample("jvm_threads_live", NONE, NONE, threads.getThreadCount());
    out.family("jvm_threads_daemon", MetricType.GAUGE, "Live daemon threads.");
    out.sample("jvm_threads_daemon", NONE, NONE, threads.getDaemonThreadCount());
    out.family("jvm_threads_peak", MetricType.GAUGE, "The most threads alive at once.");
    out.sample("jvm_threads_peak", NONE, NONE, threads.getPeakThreadCount());
    out.family("jvm_classes_loaded", MetricType.GAUGE, "Classes loaded in the JVM at the moment.");
    out.sample(
        "jvm_classes_loaded",
        NONE,
        NONE,
        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
    process(out);
  }

  private void memory(SampleVisitor out) {
    out.family("jvm_memory_used_bytes", MetricType.GAUGE, "Memory in use, by area and pool.");
    for (MemoryPoolMXBean pool : pools) {
      MemoryUsage usage = pool.getUsage();
      if (usage != null) {
        out.sample("jvm_memory_used_bytes", AREA_POOL, labels(pool), usage.getUsed());
      }
    }
    out.family(
        "jvm_memory_committed_bytes",
        MetricType.GAUGE,
        "Memory the JVM reserved from the system, by area and pool.");
    for (MemoryPoolMXBean pool : pools) {
      MemoryUsage usage = pool.getUsage();
      if (usage != null) {
        out.sample("jvm_memory_committed_bytes", AREA_POOL, labels(pool), usage.getCommitted());
      }
    }
    out.family(
        "jvm_memory_max_bytes",
        MetricType.GAUGE,
        "The most memory a pool may use, by area and pool; -1 when unbounded.");
    for (MemoryPoolMXBean pool : pools) {
      MemoryUsage usage = pool.getUsage();
      if (usage != null) {
        out.sample("jvm_memory_max_bytes", AREA_POOL, labels(pool), usage.getMax());
      }
    }
  }

  private static List<String> labels(MemoryPoolMXBean pool) {
    return List.of(pool.getType() == MemoryType.HEAP ? "heap" : "nonheap", pool.getName());
  }

  private void gc(SampleVisitor out) {
    out.family(
        "jvm_gc_collections_total", MetricType.COUNTER, "Garbage collections, by collector.");
    for (GarbageCollectorMXBean collector : collectors) {
      out.sample(
          "jvm_gc_collections_total",
          GC,
          List.of(collector.getName()),
          Math.max(0, collector.getCollectionCount()));
    }
    out.family(
        "jvm_gc_collection_seconds_total",
        MetricType.COUNTER,
        "Time spent in garbage collections, by collector.");
    for (GarbageCollectorMXBean collector : collectors) {
      out.sample(
          "jvm_gc_collection_seconds_total",
          GC,
          List.of(collector.getName()),
          Math.max(0, collector.getCollectionTime()) / 1000.0);
    }
  }

  private void process(SampleVisitor out) {
    out.family(
        "process_start_time_seconds", MetricType.GAUGE, "When the process started, Unix time.");
    out.sample("process_start_time_seconds", NONE, NONE, runtime.getStartTime() / 1000.0);
    out.family(
        "process_cpu_count",
        MetricType.GAUGE,
        "Processors the JVM may use: the container's limit when there is one.");
    out.sample("process_cpu_count", NONE, NONE, Runtime.getRuntime().availableProcessors());
    if (os instanceof OperatingSystemMXBean sun) {
      out.family(
          "process_cpu_seconds_total", MetricType.COUNTER, "CPU time the process used, in all.");
      long cpu = sun.getProcessCpuTime();
      if (cpu >= 0) {
        out.sample("process_cpu_seconds_total", NONE, NONE, cpu / 1_000_000_000.0);
      }
      out.family(
          "process_memory_limit_bytes",
          MetricType.GAUGE,
          "Memory the process may use: the container's limit when there is one, else the"
              + " machine's.");
      out.sample("process_memory_limit_bytes", NONE, NONE, sun.getTotalMemorySize());
    }
    if (os instanceof UnixOperatingSystemMXBean unix) {
      out.family("process_open_fds", MetricType.GAUGE, "File descriptors open.");
      out.sample("process_open_fds", NONE, NONE, unix.getOpenFileDescriptorCount());
      out.family("process_max_fds", MetricType.GAUGE, "File descriptors the process may open.");
      out.sample("process_max_fds", NONE, NONE, unix.getMaxFileDescriptorCount());
    }
    out.family("jvm_info", MetricType.GAUGE, "The JVM: always 1, the details in the labels.");
    out.sample(
        "jvm_info",
        List.of("version", "vendor", "runtime"),
        List.of(
            System.getProperty("java.version", "unknown"),
            System.getProperty("java.vendor", "unknown").toLowerCase(Locale.ROOT),
            System.getProperty("java.runtime.name", "unknown")),
        1);
  }
}
