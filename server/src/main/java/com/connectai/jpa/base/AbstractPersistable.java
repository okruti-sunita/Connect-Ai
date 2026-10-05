package com.connectai.jpa.base;

import jakarta.persistence.MappedSuperclass;

/**
 * Common base for persisted Connect AI entities.
 *
 * <p>Concrete persistence identity is supplied by the specialized
 * {@link AbstractUUIDPersistable} base class.</p>
 */
@MappedSuperclass
public abstract class AbstractPersistable {

    protected AbstractPersistable() {
    }
}
