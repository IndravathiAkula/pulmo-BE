package com.ebook.common.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

public class BaseRepository<T, P> implements PanacheRepositoryBase<T, P> {

    @Inject
    EntityManager entityManager;

    @Transactional
    public void save(T entity) {
        persist(entity);
    }

    @Transactional
    public T update(T entity) {
        return entityManager.merge(entity);
    }
}