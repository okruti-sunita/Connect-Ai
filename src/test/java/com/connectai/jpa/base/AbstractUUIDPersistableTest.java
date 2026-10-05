package com.connectai.jpa.base;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AbstractUUIDPersistableTest {

    @Test
    void newEntityHasNoIdAndIsNew() {
        TestEntity entity = new TestEntity();

        assertNull(entity.getId());
        assertNull(entity.getIdAsString());
        assertTrue(entity.isNew());
    }

    @Test
    void setUuidIdMakesEntityPersistable() {
        UUID id = UUID.randomUUID();
        TestEntity entity = new TestEntity();

        entity.setId(id);

        assertEquals(id, entity.getId());
        assertEquals(id.toString(), entity.getIdAsString());
        assertFalse(entity.isNew());
    }

    @Test
    void setStringIdConvertsToUuid() {
        UUID id = UUID.randomUUID();
        TestEntity entity = new TestEntity();

        entity.setId(id.toString());

        assertEquals(id, entity.getId());
    }

    @Test
    void sameIdsAreEqual() {
        UUID id = UUID.randomUUID();
        TestEntity first = new TestEntity(id);
        TestEntity second = new TestEntity(id);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    private static class TestEntity extends AbstractUUIDPersistable {

        private TestEntity() {
        }

        private TestEntity(UUID id) {
            super(id);
        }
    }
}
