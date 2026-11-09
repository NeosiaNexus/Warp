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
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Gauge;
import dev.warp.api.metrics.Histogram;
import dev.warp.api.metrics.MetricNames;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.metrics.SampleVisitor;
import dev.warp.proxy.logging.LogCode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one registry of the proxy: the proxy's metrics, every plugin's under its prefix, and the
 * collectors. Implements the rules of {@link Metrics}: names checked at registration, 32 values per
 * label then {@code other}, 2 000 series per plugin and {@value #MAX_TOTAL_SERIES} in all.
 *
 * <p>Counter and histogram children stripe their storage per event loop ({@link LoopIndex}): {@code
 * stripes} is how many loops get a slot. Registration and {@link #collect} are thread-safe and
 * never run on the hot path; scrapes read snapshots of the families.
 */
public final class MetricRegistry implements Metrics {

  private static final Logger logger = LoggerFactory.getLogger(MetricRegistry.class);

  /** Distinct values one label of a plugin's metric may have; later values become {@code other}. */
  public static final int MAX_LABEL_VALUES = 32;

  /** Series one plugin may have in all. */
  public static final int MAX_PLUGIN_SERIES = 2000;

  /** Series the registry holds in all, the proxy's included. */
  public static final int MAX_TOTAL_SERIES = 10_000;

  /** The value a label takes beyond the cap. */
  public static final String OTHER = "other";

  /** The proxy's own metrics have bounded labels (protocol versions, servers) above a plugin's. */
  static final int PROXY_LABEL_VALUES = 256;

  private static final String OVERFLOW_METRIC = "warp_metrics_label_overflow_total";
  private static final String COLLECTOR_ERRORS_METRIC = "warp_metrics_collector_errors_total";

  private final int stripes;
  private final ConcurrentHashMap<String, Family> families = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Registered> collectors = new ConcurrentHashMap<>();
  private final Set<String> warned = ConcurrentHashMap.newKeySet();
  private final AtomicInteger series = new AtomicInteger();
  private final Owner proxy = new Owner("warp", "", Integer.MAX_VALUE, PROXY_LABEL_VALUES);
  private final Counter labelOverflow;
  private final Counter collectorErrors;

  /**
   * Creates the registry.
   *
   * @param stripes how many event loops get a slot of their own in every counter: the worker loops'
   *     count; 0 makes every thread use the shared adders (tests)
   */
  public MetricRegistry(int stripes) {
    if (stripes < 0) {
      throw new IllegalArgumentException("stripes must be 0 or more, found " + stripes);
    }
    this.stripes = stripes;
    this.labelOverflow =
        counter(
            OVERFLOW_METRIC,
            "Label values recorded as 'other' because the metric had too many distinct values.",
            "metric");
    this.collectorErrors =
        counter(
            COLLECTOR_ERRORS_METRIC,
            "Scrapes a collector failed and was skipped from.",
            "collector");
  }

  /**
   * Returns how many event loops have a slot in every counter.
   *
   * @return the stripe count
   */
  public int stripes() {
    return stripes;
  }

  /**
   * Returns a plugin's view: names prefixed with the plugin's id, a plugin's caps, and everything
   * registered through it removed by {@link ScopedMetrics#close()}.
   *
   * @param pluginId the plugin's id
   * @return the view
   */
  public ScopedMetrics scoped(String pluginId) {
    return new ScopedMetrics(
        this,
        new Owner(
            pluginId, MetricNames.pluginPrefix(pluginId), MAX_PLUGIN_SERIES, MAX_LABEL_VALUES));
  }

  // ---------------------------------------------------------------------------
  // Metrics (the proxy's own)
  // ---------------------------------------------------------------------------

  @Override
  public Counter counter(String name, String help, String... labelNames) {
    return registerCounter(proxy, name, help, labelNames);
  }

  @Override
  public Gauge gauge(String name, String help, String... labelNames) {
    return registerGauge(proxy, name, help, labelNames);
  }

  @Override
  public void gauge(String name, String help, DoubleSupplier value) {
    registerPullGauge(proxy, name, help, value);
  }

  @Override
  public Histogram histogram(String name, String help, Buckets buckets, String... labelNames) {
    return registerHistogram(proxy, name, help, buckets, labelNames);
  }

  @Override
  public void collector(String id, Collector collector) {
    registerCollector(proxy, id, collector);
  }

  // ---------------------------------------------------------------------------
  // Registration, for any owner
  // ---------------------------------------------------------------------------

  Counter registerCounter(Owner owner, String name, String help, String... labelNames) {
    return register(
        owner,
        name,
        MetricType.COUNTER,
        labelNames,
        () -> new CounterFamily(owner, name, help, labelNames));
  }

  Gauge registerGauge(Owner owner, String name, String help, String... labelNames) {
    return register(
        owner,
        name,
        MetricType.GAUGE,
        labelNames,
        () -> new GaugeFamily(owner, name, help, labelNames));
  }

  void registerPullGauge(Owner owner, String name, String help, DoubleSupplier value) {
    Family existing = families.get(name);
    if (existing != null) {
      throw new IllegalArgumentException("metric '" + name + "' is registered already");
    }
    register(
        owner,
        name,
        MetricType.GAUGE,
        new String[0],
        () -> new PullGauge(owner, name, help, value));
  }

  Histogram registerHistogram(
      Owner owner, String name, String help, Buckets buckets, String... labelNames) {
    HistogramFamily family =
        register(
            owner,
            name,
            MetricType.HISTOGRAM,
            labelNames,
            () -> new HistogramFamily(owner, name, help, labelNames, buckets));
    if (!family.buckets().equals(buckets)) {
      throw new IllegalArgumentException(
          "histogram '" + name + "' is registered with other buckets: " + family.buckets());
    }
    return family;
  }

  void registerCollector(Owner owner, String id, Collector collector) {
    MetricNames.validate(id, MetricType.GAUGE);
    Registered registered = new Registered(owner, collector);
    if (collectors.putIfAbsent(id, registered) != null) {
      throw new IllegalArgumentException("collector '" + id + "' is registered already");
    }
  }

  @SuppressWarnings("unchecked")
  private <F extends Family> F register(
      Owner owner,
      String name,
      MetricType type,
      String[] labelNames,
      java.util.function.Supplier<F> factory) {
    MetricNames.validate(name, type);
    for (String label : labelNames) {
      MetricNames.validateLabel(label);
    }
    if (new java.util.HashSet<>(Arrays.asList(labelNames)).size() != labelNames.length) {
      throw new IllegalArgumentException(
          "metric '" + name + "' names a label twice: " + Arrays.toString(labelNames));
    }
    boolean[] fresh = {false};
    Family family =
        families.computeIfAbsent(
            name,
            _ -> {
              fresh[0] = true;
              return factory.get();
            });
    if (!fresh[0]
        && (family.type != type
            || family.owner != owner
            || !family.labelNames.equals(Arrays.asList(labelNames))
            || family instanceof PullGauge)) {
      throw new IllegalArgumentException(
          "metric '"
              + name
              + "' is registered already as a "
              + family.type.name().toLowerCase(java.util.Locale.ROOT)
              + " with labels "
              + family.labelNames
              + (family.owner == owner ? "" : " by " + family.owner.id));
    }
    return (F) family;
  }

  /** Removes everything an owner registered: its plugin was disabled. */
  void remove(Owner owner) {
    families.values().removeIf(family -> family.owner == owner);
    collectors.values().removeIf(registered -> registered.owner == owner);
    series.addAndGet(-owner.series.getAndSet(0));
  }

  // ---------------------------------------------------------------------------
  // Scrape
  // ---------------------------------------------------------------------------

  @Override
  public void collect(SampleVisitor visitor) {
    // Collectors first, so that a failure of theirs shows in this scrape's error counter.
    for (Map.Entry<String, Registered> entry : new TreeMap<>(collectors).entrySet()) {
      String id = entry.getKey();
      Registered registered = entry.getValue();
      try {
        registered.collector.collect(new PrefixCheck(visitor, registered.owner.prefix));
      } catch (RuntimeException e) {
        collectorErrors.labels(id).inc();
        if (warned.add("collector:" + id)) {
          logger.warn(
              LogCode.METRICS_COLLECTOR_FAILED.marker(),
              "Metrics collector {} failed and is skipped from scrapes; counted in {}",
              id,
              COLLECTOR_ERRORS_METRIC,
              e);
        }
      }
    }
    for (Family family : new TreeMap<>(families).values()) {
      family.collect(visitor);
    }
  }

  /**
   * Checks what a plugin collector writes: a family must be a valid name under the plugin's prefix,
   * and every sample must belong to the current family, with valid label names. A sample before any
   * family, under another name or with a forged label name would otherwise reach the exposition
   * verbatim, and could spoof the proxy's series or inject lines into a scrape.
   */
  private static final class PrefixCheck implements SampleVisitor {
    private static final String QUANTILE = "quantile";

    private final SampleVisitor delegate;
    private final String prefix;
    private @Nullable String family;
    private @Nullable MetricType familyType;

    PrefixCheck(SampleVisitor delegate, String prefix) {
      this.delegate = delegate;
      this.prefix = prefix;
    }

    @Override
    public void family(String name, MetricType type, String help) {
      MetricNames.validate(name, type);
      if (!name.startsWith(prefix)) {
        throw new IllegalArgumentException(
            "collector family '" + name + "' must start with the plugin's prefix " + prefix);
      }
      family = name;
      familyType = type;
      delegate.family(name, type, help);
    }

    @Override
    public void sample(
        String name, List<String> labelNames, List<String> labelValues, double value) {
      if (family == null) {
        throw new IllegalStateException(
            "collector sample '" + name + "' was written before any family");
      }
      // The label a histogram's bucket or a summary's quantile may carry, reserved otherwise.
      String allowedLabel = null;
      if (name.equals(family)) {
        if (familyType == MetricType.SUMMARY) {
          allowedLabel = QUANTILE;
        }
      } else if (familyType == MetricType.HISTOGRAM
          && name.equals(family + HistogramFamily.BUCKET)) {
        allowedLabel = HistogramFamily.LE;
      } else if (!((familyType == MetricType.HISTOGRAM || familyType == MetricType.SUMMARY)
          && (name.equals(family + HistogramFamily.SUM)
              || name.equals(family + HistogramFamily.COUNT)))) {
        throw new IllegalArgumentException(
            "collector sample '" + name + "' does not belong to the family " + family);
      }
      if (labelNames.size() != labelValues.size()) {
        throw new IllegalArgumentException(
            "collector sample '"
                + name
                + "' has "
                + labelNames.size()
                + " label names and "
                + labelValues.size()
                + " values");
      }
      for (String label : labelNames) {
        if (!label.equals(allowedLabel)) {
          MetricNames.validateLabel(label);
        }
      }
      delegate.sample(name, labelNames, labelValues, value);
    }
  }

  private boolean admitSeries(Owner owner, String name) {
    if (owner.series.incrementAndGet() > owner.maxSeries) {
      owner.series.decrementAndGet();
      capped(name, owner.id + " has " + owner.maxSeries + " series");
      return false;
    }
    if (series.incrementAndGet() > MAX_TOTAL_SERIES) {
      series.decrementAndGet();
      owner.series.decrementAndGet();
      capped(name, "the registry holds " + MAX_TOTAL_SERIES + " series");
      return false;
    }
    return true;
  }

  private void capped(String name, String why) {
    if (warned.add("metric:" + name)) {
      logger.warn(
          LogCode.METRICS_CARDINALITY_CAPPED.marker(),
          "Metric {} reached a cardinality cap ({}): further label values are recorded as '{}'",
          name,
          why,
          OTHER);
    }
  }

  // ---------------------------------------------------------------------------
  // Owners and families
  // ---------------------------------------------------------------------------

  /** Who registered a metric: the proxy, or a plugin with its prefix and caps. */
  static final class Owner {
    final String id;
    final String prefix;
    final int maxSeries;
    final int maxLabelValues;
    final AtomicInteger series = new AtomicInteger();

    Owner(String id, String prefix, int maxSeries, int maxLabelValues) {
      this.id = id;
      this.prefix = prefix;
      this.maxSeries = maxSeries;
      this.maxLabelValues = maxLabelValues;
    }
  }

  private record Registered(Owner owner, Collector collector) {}

  /** A metric: its description and its children, one per label combination. */
  private abstract class Family {
    final Owner owner;
    final String name;
    final String help;
    final MetricType type;
    final List<String> labelNames;
    final ConcurrentHashMap<List<String>, Object> children = new ConcurrentHashMap<>();
    private final List<Set<String>> seen;
    private final List<String> allOther;

    Family(Owner owner, String name, String help, MetricType type, String[] labelNames) {
      this.owner = owner;
      this.name = name;
      this.help = help;
      this.type = type;
      this.labelNames = List.of(labelNames);
      List<Set<String>> sets = new ArrayList<>(labelNames.length);
      for (int i = 0; i < labelNames.length; i++) {
        sets.add(ConcurrentHashMap.newKeySet());
      }
      this.seen = sets;
      this.allOther = Collections.nCopies(labelNames.length, OTHER);
    }

    /** Creates a child's storage for these (resolved) label values. */
    abstract Object newChild(List<String> values);

    /** Writes the family's samples. */
    abstract void collect(SampleVisitor visitor);

    /**
     * Returns the child of these label values, created if new and the caps allow; collapses values
     * beyond the caps to {@code other}.
     */
    Object child(String[] values) {
      if (values.length != labelNames.size()) {
        throw new IllegalArgumentException(
            "metric '"
                + name
                + "' has labels "
                + labelNames
                + ", given "
                + values.length
                + " values");
      }
      String[] resolved = values.clone();
      for (int i = 0; i < resolved.length; i++) {
        String value = resolved[i];
        if (value.isEmpty()) {
          throw new IllegalArgumentException(
              "metric '" + name + "': the value of label " + labelNames.get(i) + " is empty");
        }
        Set<String> values1 = seen.get(i);
        if (!values1.contains(value)) {
          if (values1.size() < owner.maxLabelValues) {
            values1.add(value);
          } else {
            resolved[i] = OTHER;
            if (labelOverflow != null) {
              labelOverflow.labels(name).inc();
            }
            capped(name, "label " + labelNames.get(i) + " has " + owner.maxLabelValues + " values");
          }
        }
      }
      List<String> key = List.of(resolved);
      Object existing = children.get(key);
      if (existing != null) {
        return existing;
      }
      if (!key.equals(allOther) && !admitSeries(owner, name)) {
        key = allOther;
        existing = children.get(key);
        if (existing != null) {
          return existing;
        }
      }
      return children.computeIfAbsent(key, this::newChild);
    }

    /** The children, sorted by label values, for a deterministic exposition. */
    List<Map.Entry<List<String>, Object>> sortedChildren() {
      List<Map.Entry<List<String>, Object>> sorted = new ArrayList<>(children.entrySet());
      sorted.sort(
          (a, b) -> {
            List<String> left = a.getKey();
            List<String> right = b.getKey();
            for (int i = 0; i < left.size(); i++) {
              int c = left.get(i).compareTo(right.get(i));
              if (c != 0) {
                return c;
              }
            }
            return 0;
          });
      return sorted;
    }
  }

  private final class CounterFamily extends Family implements Counter {
    CounterFamily(Owner owner, String name, String help, String[] labelNames) {
      super(owner, name, help, MetricType.COUNTER, labelNames);
      if (labelNames.length == 0) {
        var _ = child(new String[0]);
      }
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<String> labelNames() {
      return labelNames;
    }

    @Override
    public Child labels(String... values) {
      return (Child) child(values);
    }

    @Override
    Object newChild(List<String> values) {
      return new CounterChild(stripes);
    }

    @Override
    void collect(SampleVisitor visitor) {
      visitor.family(name, type, help);
      for (Map.Entry<List<String>, Object> entry : sortedChildren()) {
        visitor.sample(name, labelNames, entry.getKey(), ((CounterChild) entry.getValue()).value());
      }
    }
  }

  private static final class CounterChild implements Counter.Child {
    private final StripedLong value;

    CounterChild(int stripes) {
      this.value = new StripedLong(stripes);
    }

    @Override
    public void inc() {
      value.add(1);
    }

    @Override
    public void add(long amount) {
      if (amount < 0) {
        throw new IllegalArgumentException("a counter only goes up; cannot add " + amount);
      }
      value.add(amount);
    }

    @Override
    public long value() {
      return value.sum();
    }
  }

  private final class GaugeFamily extends Family implements Gauge {
    GaugeFamily(Owner owner, String name, String help, String[] labelNames) {
      super(owner, name, help, MetricType.GAUGE, labelNames);
      if (labelNames.length == 0) {
        var _ = child(new String[0]);
      }
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<String> labelNames() {
      return labelNames;
    }

    @Override
    public Child labels(String... values) {
      return (Child) child(values);
    }

    @Override
    Object newChild(List<String> values) {
      return new GaugeChild();
    }

    @Override
    void collect(SampleVisitor visitor) {
      visitor.family(name, type, help);
      for (Map.Entry<List<String>, Object> entry : sortedChildren()) {
        visitor.sample(name, labelNames, entry.getKey(), ((GaugeChild) entry.getValue()).value());
      }
    }
  }

  private static final class GaugeChild implements Gauge.Child {
    private static final VarHandle BITS;

    static {
      try {
        BITS = MethodHandles.lookup().findVarHandle(GaugeChild.class, "bits", long.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    @SuppressWarnings("unused")
    private volatile long bits = Double.doubleToRawLongBits(0.0);

    @Override
    public void set(double value) {
      BITS.setVolatile(this, Double.doubleToRawLongBits(value));
    }

    @Override
    public void inc() {
      add(1.0);
    }

    @Override
    public void dec() {
      add(-1.0);
    }

    @Override
    public void add(double amount) {
      while (true) {
        long current = (long) BITS.getVolatile(this);
        long next = Double.doubleToRawLongBits(Double.longBitsToDouble(current) + amount);
        if (BITS.compareAndSet(this, current, next)) {
          return;
        }
      }
    }

    @Override
    public double value() {
      return Double.longBitsToDouble((long) BITS.getVolatile(this));
    }
  }

  private final class PullGauge extends Family {
    private final DoubleSupplier value;

    PullGauge(Owner owner, String name, String help, DoubleSupplier value) {
      super(owner, name, help, MetricType.GAUGE, new String[0]);
      this.value = value;
      var _ = admitSeries(owner, name);
    }

    @Override
    Object newChild(List<String> values) {
      throw new UnsupportedOperationException("a pull gauge has no children");
    }

    @Override
    void collect(SampleVisitor visitor) {
      double current;
      try {
        current = value.getAsDouble();
      } catch (RuntimeException e) {
        // A plugin's supplier is as untrusted as its collector: skip the gauge, not the scrape.
        collectorErrors.labels(name).inc();
        if (warned.add("gauge:" + name)) {
          logger.warn(
              LogCode.METRICS_COLLECTOR_FAILED.marker(),
              "Gauge {} failed to read its value and is skipped from scrapes; counted in {}",
              name,
              COLLECTOR_ERRORS_METRIC,
              e);
        }
        return;
      }
      visitor.family(name, type, help);
      visitor.sample(name, labelNames, labelNames, current);
    }
  }

  private final class HistogramFamily extends Family implements Histogram {
    private static final String BUCKET = "_bucket";
    private static final String SUM = "_sum";
    private static final String COUNT = "_count";
    private static final String LE = "le";

    private final Buckets buckets;
    private final List<String> bucketLabelNames;
    private final String[] bounds;

    HistogramFamily(Owner owner, String name, String help, String[] labelNames, Buckets buckets) {
      super(owner, name, help, MetricType.HISTOGRAM, labelNames);
      this.buckets = buckets;
      List<String> withLe = new ArrayList<>(this.labelNames);
      withLe.add(LE);
      this.bucketLabelNames = List.copyOf(withLe);
      this.bounds = new String[buckets.size() + 1];
      for (int i = 0; i < buckets.size(); i++) {
        bounds[i] = PrometheusText.number(buckets.bound(i));
      }
      bounds[buckets.size()] = "+Inf";
      if (labelNames.length == 0) {
        var _ = child(new String[0]);
      }
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<String> labelNames() {
      return labelNames;
    }

    @Override
    public Buckets buckets() {
      return buckets;
    }

    @Override
    public Child labels(String... values) {
      return (Child) child(values);
    }

    @Override
    Object newChild(List<String> values) {
      return new HistogramChild(new StripedHistogram(buckets, stripes));
    }

    @Override
    void collect(SampleVisitor visitor) {
      visitor.family(name, type, help);
      for (Map.Entry<List<String>, Object> entry : sortedChildren()) {
        List<String> values = entry.getKey();
        StripedHistogram storage = ((HistogramChild) entry.getValue()).storage;
        long cumulative = 0;
        for (int bucket = 0; bucket < bounds.length; bucket++) {
          cumulative += storage.bucketCount(bucket);
          List<String> withLe = new ArrayList<>(values);
          withLe.add(bounds[bucket]);
          visitor.sample(name + BUCKET, bucketLabelNames, withLe, cumulative);
        }
        visitor.sample(name + SUM, labelNames, values, storage.sum());
        visitor.sample(name + COUNT, labelNames, values, cumulative);
      }
    }
  }

  private static final class HistogramChild implements Histogram.Child {
    private final StripedHistogram storage;

    HistogramChild(StripedHistogram storage) {
      this.storage = storage;
    }

    @Override
    public void observe(double value) {
      storage.observe(value);
    }

    @Override
    public void observeNanos(long nanos) {
      storage.observe(nanos / 1_000_000_000.0);
    }

    @Override
    public long count() {
      return storage.count();
    }

    @Override
    public double sum() {
      return storage.sum();
    }
  }
}
