package com.github.ambry.rest;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;


/**
 * Unit tests for {@link RestRequestMetricsTracker}.
 */
public class RestRequestMetricsTrackerTest {

  /**
   * Tests the common case uses of {@link RestRequestMetricsTracker} i.e. with and without a custom
   * {@link RestRequestMetrics}.
   */
  @Test
  public void commonCaseTest() {
    withDefaultsTest();
    withInjectedMetricsTest();
  }

  /**
   * Tests reaction of {@link RestRequestMetricsTracker#injectMetrics(RestRequestMetrics)} to bad input.
   */
  @Test
  public void injectMetricsBadInputTest() {
    RestRequestMetricsTracker requestMetrics = new RestRequestMetricsTracker();
    try {
      requestMetrics.injectMetrics(null);
      fail("There was no exception even though a null RestRequestMetrics was provided as input for injectMetrics()");
    } catch (IllegalArgumentException e) {
      // expected. nothing to do.
    }
  }

  // commonCaseTest() helpers

  /**
   * Tests recording of metrics without setting a custom {@link RestRequestMetrics}.
   */
  private void withDefaultsTest() {
    MetricRegistry metricRegistry = new MetricRegistry();
    RestRequestMetricsTracker.setDefaults(metricRegistry);
    RestRequestMetricsTracker requestMetrics = new RestRequestMetricsTracker();
    TestMetrics testMetrics = new TestMetrics(requestMetrics);
    long additionalTime = 20;
    requestMetrics.addToTotalTime(additionalTime);
    requestMetrics.recordMetrics();
    String metricPrefix =
        RestRequestMetricsTracker.class.getCanonicalName() + "." + RestRequestMetricsTracker.DEFAULT_REQUEST_TYPE;
    testMetrics.compareMetrics(metricPrefix, metricRegistry, additionalTime);
  }

  /**
   * Tests recording of metrics after setting a custom {@link RestRequestMetrics}.
   */
  private void withInjectedMetricsTest() {
    MetricRegistry metricRegistry = new MetricRegistry();
    RestRequestMetricsTracker.setDefaults(metricRegistry);
    String testRequestType = "Test";
    RestRequestMetricsTracker requestMetrics = new RestRequestMetricsTracker();
    RestRequestMetrics restRequestMetrics = new RestRequestMetrics(getClass(), testRequestType, metricRegistry);
    TestMetrics testMetrics = new TestMetrics(requestMetrics);
    long additionalTime = 20;
    requestMetrics.addToTotalTime(additionalTime);
    requestMetrics.injectMetrics(restRequestMetrics);
    requestMetrics.recordMetrics();
    String metricPrefix = getClass().getCanonicalName() + "." + testRequestType;
    testMetrics.compareMetrics(metricPrefix, metricRegistry, additionalTime);
  }
}

/**
 * Class that randomly generates some metrics, updates them in the instance of {@link RestRequestMetricsTracker}
 * provided and then checks for equality once the metrics are recorded.
 */
class TestMetrics {
  private final Random random = new Random();
  private final long nioLayerRequestProcessingTime = random.nextInt(Integer.MAX_VALUE);
  private final long nioLayerResponseProcessingTime = random.nextInt(Integer.MAX_VALUE);
  private final long scRequestProcessingTime = random.nextInt(Integer.MAX_VALUE);
  private final long scResponseProcessingTime = random.nextInt(Integer.MAX_VALUE);
  private final long scRequestQueuingTime = random.nextInt(Integer.MAX_VALUE);
  private final long scResponseQueuingTime = random.nextInt(Integer.MAX_VALUE);

  /**
   * Creates a new instance by generating new random metrics and updating it in the given {@code requestMetrics}.
   * @param requestMetrics the instance of {@link RestRequestMetricsTracker} where metrics have to be updated.
   */
  protected TestMetrics(RestRequestMetricsTracker requestMetrics) {
    updateMetrics(requestMetrics);
  }

  /**
   * Compares metrics generated inside this instance with what was recorded in the given {@code metricRegistry}.
   * @param metricPrefix the prefix of the metrics to look for.
   * @param metricRegistry the {@link MetricRegistry} where metrics were recorded.
   * @param additionalTime any additional time added to the total time via a call to
   *                        {@link RestRequestMetricsTracker#addToTotalTime(long)}.
   */
  protected void compareMetrics(String metricPrefix, MetricRegistry metricRegistry, long additionalTime) {
    long totalTime = getTotalTime() + additionalTime;
    Map<String, Histogram> histograms = metricRegistry.getHistograms();

    assertEquals("NIO request processing time unequal", nioLayerRequestProcessingTime,
        histograms.get(metricPrefix + RestRequestMetrics.NIO_REQUEST_PROCESSING_TIME_SUFFIX).getSnapshot()
            .getValues()[0]);
    assertEquals("NIO response processing time unequal", nioLayerResponseProcessingTime,
        histograms.get(metricPrefix + RestRequestMetrics.NIO_RESPONSE_PROCESSING_TIME_SUFFIX).getSnapshot()
            .getValues()[0]);

    assertEquals("SC request processing time unequal", scRequestProcessingTime,
        histograms.get(metricPrefix + RestRequestMetrics.SC_REQUEST_PROCESSING_TIME_SUFFIX).getSnapshot()
            .getValues()[0]);
    assertEquals("SC request queuing time unequal", scRequestQueuingTime,
        histograms.get(metricPrefix + RestRequestMetrics.SC_REQUEST_QUEUING_TIME_SUFFIX).getSnapshot().getValues()[0]);
    assertEquals("SC response processing time unequal", scResponseProcessingTime,
        histograms.get(metricPrefix + RestRequestMetrics.SC_RESPONSE_PROCESSING_TIME_SUFFIX).getSnapshot()
            .getValues()[0]);
    assertEquals("SC response queuing time unequal", scResponseQueuingTime,
        histograms.get(metricPrefix + RestRequestMetrics.SC_RESPONSE_QUEUING_TIME_SUFFIX).getSnapshot().getValues()[0]);

    assertEquals("Request total service time unequal", totalTime,
        histograms.get(metricPrefix + RestRequestMetrics.TOTAL_TIME_SUFFIX).getSnapshot().getValues()[0]);
  }

  /**
   * Updates the generated metrics in the given {@code restRequestMetricsTracker}.
   * @param restRequestMetricsTracker the instance of {@link RestRequestMetricsTracker} where metrics have to be updated.
   */
  private void updateMetrics(RestRequestMetricsTracker restRequestMetricsTracker) {
    restRequestMetricsTracker.nioLayerMetrics.addToRequestProcessingTime(nioLayerRequestProcessingTime);
    restRequestMetricsTracker.nioLayerMetrics.addToResponseProcessingTime(nioLayerResponseProcessingTime);

    restRequestMetricsTracker.scalingLayerMetrics.addToRequestProcessingTime(scRequestProcessingTime);
    restRequestMetricsTracker.scalingLayerMetrics.addToResponseProcessingTime(scResponseProcessingTime);
    restRequestMetricsTracker.scalingLayerMetrics.addToRequestQueuingTime(scRequestQueuingTime);
    restRequestMetricsTracker.scalingLayerMetrics.addToResponseQueuingTime(scResponseQueuingTime);
  }

  /**
   * Gets the total time by adding all the metrics generated by this instance.
   * @return the total time obtained by adding all the metrics generated by this instance.
   */
  private long getTotalTime() {
    return nioLayerRequestProcessingTime + nioLayerResponseProcessingTime + scRequestProcessingTime +
        scResponseProcessingTime + scRequestQueuingTime + scResponseQueuingTime;
  }
}
