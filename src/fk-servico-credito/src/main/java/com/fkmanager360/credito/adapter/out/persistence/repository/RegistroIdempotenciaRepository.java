package com.fkmanager360.credito.adapter.out.persistence.repository;

import com.fkmanager360.credito.adapter.out.persistence.entity.RegistroIdempotenciaEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.RegistroIdempotenciaId;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code findById(RegistroIdempotenciaId)}, herdado de {@link JpaRepository}, e suficiente. */
public interface RegistroIdempotenciaRepository extends JpaRepository<RegistroIdempotenciaEntity, RegistroIdempotenciaId> {
}
