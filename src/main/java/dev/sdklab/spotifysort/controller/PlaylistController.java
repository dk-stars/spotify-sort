package dev.sdklab.spotifysort.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import dev.sdklab.spotifysort.model.PlaylistSummary;
import dev.sdklab.spotifysort.service.SpotifyClientService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final SpotifyClientService spotifyClientService;

    @GetMapping
    public ResponseEntity<?> getUserPlaylists(
            @SessionAttribute(name = "userId", required = false) Long userId) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        try {
            List<PlaylistSummary> playlists = spotifyClientService.getUserPlaylists(userId);
            return ResponseEntity.ok(playlists);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
