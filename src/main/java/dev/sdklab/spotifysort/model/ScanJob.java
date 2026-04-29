package dev.sdklab.spotifysort.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "scan_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String sourcePlaylistId;

    @Column(nullable = false)
    private int threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(length = 2048)
    private String errorMessage;

    @Column(length = 255)
    private String currentStep;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int progressPercent;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean cancelRequested;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
