// SPDX-License-Identifier: Apache-2.0
package org.atp.adapter;

import org.atp.effect.Effects.PhasePort;
import org.atp.effect.Effects.Runtime;

public final class RoutingRuntime implements Runtime {
    private final PhasePort oltp;
    private final PhasePort lakehouse;
    private final PhasePort api;

    public RoutingRuntime(PhasePort oltp, PhasePort lakehouse, PhasePort api) {
        this.oltp = oltp;
        this.lakehouse = lakehouse;
        this.api = api;
    }

    @Override
    public PhasePort oltp() {
        return oltp;
    }

    @Override
    public PhasePort lakehouse() {
        return lakehouse;
    }

    @Override
    public PhasePort api() {
        return api;
    }
}
