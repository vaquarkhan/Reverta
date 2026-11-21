// SPDX-License-Identifier: Apache-2.0
package org.atp.otel;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class Telemetry {
    private Telemetry() {
    }

    public record SpanEvent(String name, Map<String, String> attributes) {
    }

    @FunctionalInterface
    public interface SpanRecorder {
        SpanRecorder NONE = event -> {
        };

        void record(SpanEvent event);
    }

    @FunctionalInterface
    public interface ApprovalSink {
        ApprovalSink NONE = snapshot -> {
        };

        void required(Map<String, Object> snapshot);
    }

    /** Fan-out that must not block or fail the transaction path. */
    public static final class AsyncFanout {
        private final SpanRecorder spans;
        private final ApprovalSink approvals;
        private final java.util.concurrent.Executor executor;
        private final AtomicLong dropped = new AtomicLong();

        public AsyncFanout(SpanRecorder spans, ApprovalSink approvals, java.util.concurrent.Executor executor) {
            this.spans = spans;
            this.approvals = approvals;
            this.executor = executor;
        }

        public long dropped() {
            return dropped.get();
        }

        public void span(SpanEvent event) {
            submit(() -> spans.record(event));
        }

        public void approval(Map<String, Object> snapshot) {
            submit(() -> approvals.required(snapshot));
        }

        private void submit(Runnable task) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException ex) {
                        dropped.incrementAndGet();
                    }
                });
            } catch (RuntimeException ex) {
                dropped.incrementAndGet();
            }
        }
    }
}
