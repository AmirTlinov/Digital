/*
 * Copyright (c) 2020 Helmut Neemann.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components;

import de.neemann.digital.core.ModelEvent;
import de.neemann.digital.core.ModelEventType;
import de.neemann.digital.core.ModelStateObserverTyped;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.lang.Lang;
import de.neemann.digital.undo.Modification;
import de.neemann.digital.undo.Modifications;

import java.util.concurrent.Executor;

/**
 * Allows the model to modify the circuit
 */
public class CircuitModifierPostClosed implements CircuitModifier, ModelStateObserverTyped {

    private final Modifications.Builder<Circuit> builder;
    private final CircuitModifier circuitModifier;
    private final Executor dispatcher;
    private Modification<Circuit> pending;
    private boolean closed;

    /**
     * Creates a new instance
     *
     * @param circuitModifier the parent modifier used to modify the circuit
     */
    public CircuitModifierPostClosed(CircuitModifier circuitModifier) {
        this(circuitModifier, Runnable::run);
    }

    /**
     * @param circuitModifier the document's modifier
     * @param dispatcher dispatches the pending modification to its document owner
     */
    public CircuitModifierPostClosed(CircuitModifier circuitModifier, Executor dispatcher) {
        this.circuitModifier = circuitModifier;
        this.dispatcher = dispatcher;
        builder = new Modifications.Builder<>(Lang.get("mod_modifiedByRunningModel"));
    }

    @Override
    public synchronized void modify(Modification<Circuit> modification) {
        builder.add(modification);
    }

    @Override
    public ModelEventType[] getEvents() {
        return new ModelEventType[]{ModelEventType.POSTCLOSED};
    }

    @Override
    public void handleEvent(ModelEvent event) {
        if (event.getType().equals(ModelEventType.POSTCLOSED)) {
            synchronized (this) {
                if (closed)
                    return;
                closed = true;
                pending = builder.build();
                if (pending == null)
                    return;
            }
            dispatcher.execute(this::applyPending);
        }
    }

    /** Flushes a staged close modification once, before its owner saves or replaces the document. */
    public void applyPending() {
        Modification<Circuit> modification;
        synchronized (this) {
            modification = pending;
            pending = null;
        }
        if (modification != null)
            circuitModifier.modify(modification);
    }
}
