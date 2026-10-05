package com.connectai.jpa.base;

import org.springframework.data.domain.Persistable;

import java.io.Serializable;

public interface PersistableOperation<ID extends Serializable> extends Persistable<ID> {

    PersistableOperation<ID> setId(ID id);
}
