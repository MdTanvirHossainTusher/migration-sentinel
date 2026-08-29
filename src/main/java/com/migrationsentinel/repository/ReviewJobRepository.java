package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.ReviewJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReviewJobRepository extends JpaRepository<ReviewJobEntity, UUID> {

    Page<ReviewJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
