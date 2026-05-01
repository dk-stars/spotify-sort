package dev.sdklab.spotifysort.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.ScanJob;

public interface ScanJobRepository extends JpaRepository<ScanJob, Long> {

	Optional<ScanJob> findByIdAndUserId(Long id, Long userId);

	List<ScanJob> findByUserIdOrderByCreatedAtDesc(Long userId);
}
