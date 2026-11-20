package com.guardwork.backend.job.service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.guardwork.backend.logging.DiagnosticEvent;
import com.guardwork.backend.logging.DiagnosticLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobs.ai.enabled", havingValue = "true")
public class JdScanWorker implements DisposableBean {
  private static final Logger LOG = LoggerFactory.getLogger(JdScanWorker.class);
  private final JdScanQueue queue;
  private final JdScanAdapter adapter;
  private final JdScanSettings settings;
  private final boolean workerEnabled;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(
      Thread.ofPlatform().name("jd-scan-worker").daemon(true).factory());

  public JdScanWorker(JdScanQueue queue, JdScanAdapter adapter, JdScanSettings settings,
      @Value("${jobs.ai.worker-enabled:true}") boolean workerEnabled) {
    this.queue = queue; this.adapter = adapter; this.settings = settings; this.workerEnabled = workerEnabled;
  }

  @Scheduled(fixedDelayString = "${jobs.ai.poll-ms:1000}")
  public void tick() {
    if (settings.enabled() && workerEnabled && busy.compareAndSet(false, true)) {
      // Dispatch quickly so slow HTTP calls cannot starve other scheduled tasks.
      try {
        executor.execute(() -> {
          try { runOnce(); }
          catch (RuntimeException failure) {
            DiagnosticLog.error(LOG, DiagnosticEvent.UNEXPECTED_FAILURE, failure);
          }
          finally { busy.set(false); }
        });
      } catch (RejectedExecutionException shuttingDown) {
        busy.set(false);
      }
    }
  }

  public boolean runOnce() {
    if (!settings.enabled()) return false;
    var selected = queue.claim();
    if (selected.isEmpty()) return false;
    var claim = selected.get();
    try { queue.complete(claim, adapter.analyze(claim)); }
    catch (JdScanAdapter.Failure failure) { queue.fail(claim, failure.retryable()); }
    return true;
  }

  @Override public void destroy() { executor.shutdownNow(); }
}
