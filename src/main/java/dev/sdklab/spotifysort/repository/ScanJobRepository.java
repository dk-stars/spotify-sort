package dev.sdklab.spotifysort.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.ScanJob;

public interface ScanJobRepository extends JpaRepository<ScanJob, Long> {}
