package net.vanfek.proxybans.service.api.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.vanfek.proxybans.service.api.v1.BanDtos.LookupResponse;
import net.vanfek.proxybans.service.api.v1.BanDtos.RememberRequest;
import net.vanfek.proxybans.service.domain.service.BanService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Players", description = "Resolving names to UUIDs")
class LookupController {

    private final BanService bans;

    LookupController(BanService bans) {
        this.bans = bans;
    }

    @GetMapping("/lookup")
    @Operation(summary = "Resolve a player name to a UUID, or 404")
    Mono<ResponseEntity<LookupResponse>> lookup(@RequestParam @NotBlank @Size(max = 32) String name) {
        return this.bans.resolveByLogin(name)
                .map(identity -> identity
                        .map(LookupResponse::from)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @PostMapping("/known-players")
    @Operation(summary = "Record a UUID/name pair seen on the proxy (local mode only)")
    Mono<ResponseEntity<Void>> remember(@Valid @RequestBody RememberRequest request) {
        return this.bans.remember(request.toIdentity()).thenReturn(ResponseEntity.noContent().build());
    }

}
