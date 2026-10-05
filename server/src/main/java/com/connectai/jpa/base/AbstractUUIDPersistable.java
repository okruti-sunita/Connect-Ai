package com.connectai.jpa.base;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.UuidGenerator;

import java.util.Objects;
import java.util.UUID;

@MappedSuperclass
public abstract class AbstractUUIDPersistable extends AbstractPersistable
        implements PersistableOperation<UUID> {

    @Id
    @UuidGenerator
    @Column
    protected UUID id;

    protected AbstractUUIDPersistable() {
    }

    protected AbstractUUIDPersistable(UUID id) {
        this.setId(id);
    }

    protected AbstractUUIDPersistable(String id) {
        this.setId(UUID.fromString(id));
    }

    public String getIdAsString() {
        return this.getId() == null ? null : this.getId().toString();
    }

    public UUID getId() {
        return this.id;
    }

    @Override
    public AbstractUUIDPersistable setId(UUID id) {
        this.id = id;
        return this;
    }

    public AbstractUUIDPersistable setId(String id) {
        UUID uuid = id == null ? null : UUID.fromString(id);
        return this.setId(uuid);
    }

    @Override
    public boolean isNew() {
        return this.getId() == null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(new Object[]{this.getId()});
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        AbstractUUIDPersistable pk = (AbstractUUIDPersistable) obj;
        return Objects.equals(this.getId(), pk.getId());
    }
}
