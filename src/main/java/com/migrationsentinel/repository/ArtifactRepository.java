package com.migrationsentinel.repository;

import com.migrationsentinel.model.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {
}
