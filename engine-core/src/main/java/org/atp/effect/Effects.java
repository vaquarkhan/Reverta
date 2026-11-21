// SPDX-License-Identifier: Apache-2.0
package org.atp.effect;

import org.atp.model.Types.DiffReport;
import org.atp.model.Types.EffectStatus;
import org.atp.model.Types.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Effects {
    private Effects() {
    }

    public record Dispatch(
            String txId,
            int stepIndex,
            String tool,
            Map<String, Object> arguments,
            String idempotencyKey,
            ToolSpec spec,
            String action,
            String phase) {
        public Dispatch {
            arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
        }
    }

    public record EffectResult(
            EffectStatus status,
            Map<String, Object> output,
            String error,
            String preState,
            String postState,
            boolean retryable) {
        public EffectResult {
            output = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
        }

        public static EffectResult ok(Map<String, Object> output, String pre, String post) {
            return new EffectResult(EffectStatus.OK, output, null, pre, post, false);
        }

        public static EffectResult fail(String error, boolean retryable) {
            return new EffectResult(EffectStatus.FAILURE, Map.of(), error, null, null, retryable);
        }

        public static EffectResult unknown(String error) {
            return new EffectResult(EffectStatus.UNKNOWN, Map.of(), error, null, null, false);
        }

        public boolean found() {
            Object value = output.get("found");
            return Boolean.TRUE.equals(value);
        }
    }

    public interface PhasePort {
        EffectResult shadow(Dispatch dispatch);

        EffectResult live(Dispatch dispatch);

        EffectResult compensate(Dispatch dispatch);

        EffectResult reconcile(Dispatch dispatch);

        DiffReport diff(String txId);

        default void afterTerminal(String txId, boolean committed) {
        }
    }

    public interface Runtime extends PhasePort {
        @Override
        default EffectResult shadow(Dispatch dispatch) {
            return route(dispatch, "shadow");
        }

        @Override
        default EffectResult live(Dispatch dispatch) {
            return route(dispatch, "live");
        }

        @Override
        default EffectResult compensate(Dispatch dispatch) {
            return route(dispatch, "compensate");
        }

        @Override
        default EffectResult reconcile(Dispatch dispatch) {
            return route(dispatch, "reconcile");
        }

        private EffectResult route(Dispatch dispatch, String phase) {
            PhasePort port = switch (dispatch.spec().adapter()) {
                case "oltp" -> oltp();
                case "lakehouse" -> lakehouse();
                case "api" -> api();
                default -> null;
            };
            if (port == null) {
                return EffectResult.fail("unknown adapter " + dispatch.spec().adapter(), false);
            }
            return switch (phase) {
                case "shadow" -> port.shadow(dispatch);
                case "live" -> port.live(dispatch);
                case "compensate" -> port.compensate(dispatch);
                default -> port.reconcile(dispatch);
            };
        }

        PhasePort oltp();

        PhasePort lakehouse();

        PhasePort api();

        @Override
        default DiffReport diff(String txId) {
            return DiffReport.merge(
                    oltp() == null ? DiffReport.empty() : oltp().diff(txId),
                    lakehouse() == null ? DiffReport.empty() : lakehouse().diff(txId),
                    api() == null ? DiffReport.empty() : api().diff(txId));
        }

        @Override
        default void afterTerminal(String txId, boolean committed) {
            if (oltp() != null) {
                oltp().afterTerminal(txId, committed);
            }
            if (lakehouse() != null) {
                lakehouse().afterTerminal(txId, committed);
            }
            if (api() != null) {
                api().afterTerminal(txId, committed);
            }
        }
    }
}
