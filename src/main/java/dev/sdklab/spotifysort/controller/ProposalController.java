package dev.sdklab.spotifysort.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import dev.sdklab.spotifysort.model.ExecuteRequest;
import dev.sdklab.spotifysort.model.ExecuteSummary;
import dev.sdklab.spotifysort.service.PlaylistExecutionService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/proposal")
@RequiredArgsConstructor
public class ProposalController {

    private final PlaylistExecutionService executionService;

    @PostMapping("/execute")
    public ResponseEntity<?> execute(
            @SessionAttribute(name = "userId", required = false) Long userId,
            @RequestBody ExecuteRequest request) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        try {
            ExecuteSummary summary = executionService.execute(userId, request);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
