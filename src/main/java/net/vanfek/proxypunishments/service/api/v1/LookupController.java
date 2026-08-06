package net.vanfek.proxypunishments.service.api.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.AltResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.AltsResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.LookupResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.RememberRequest;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Players", description = "Resolving names to UUIDs and finding alternate accounts")
class LookupController {

    private final PunishmentService punishments;

    LookupController(PunishmentService punishments) {
        this.punishments = punishments;
    }

    @GetMapping("/lookup")
    @Operation(summary = "Resolve a player name to a UUID, or 404")
    Mono<ResponseEntity<LookupResponse>> lookup(@RequestParam @NotBlank @Size(max = 32) String name) {
        return this.punishments.resolveByLogin(name)
                .map(identity -> identity
                        .map(LookupResponse::from)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @GetMapping("/players/{uuid}/alts")
    @Operation(summary = "Other accounts last seen from this player's address, most recent first")
    Mono<AltsResponse> alts(@PathVariable UUID uuid, @RequestParam(defaultValue = "50") int limit) {
        Paging paging = new Paging(limit, 0);

        return this.punishments.alts(uuid, paging.limit())
                .map(AltResponse::from)
                .collectList()
                .map(AltsResponse::new);
    }

    @PostMapping("/known-players")
    @Operation(summary = "Record a UUID/name/address triple seen on the proxy (local mode only)")
    Mono<ResponseEntity<Void>> remember(@Valid @RequestBody RememberRequest request) {
        return this.punishments.remember(request.toIdentity(), IpAddressArgument.optional(request.ip()))
                .thenReturn(ResponseEntity.noContent().build());
    }

}
