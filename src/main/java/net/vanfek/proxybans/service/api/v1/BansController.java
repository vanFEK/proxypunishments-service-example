package net.vanfek.proxybans.service.api.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import net.vanfek.proxybans.service.api.v1.BanDtos.ActiveBanResponse;
import net.vanfek.proxybans.service.api.v1.BanDtos.ActiveBansListResponse;
import net.vanfek.proxybans.service.api.v1.BanDtos.HistoryEntryResponse;
import net.vanfek.proxybans.service.api.v1.BanDtos.HistoryResponse;
import net.vanfek.proxybans.service.api.v1.BanDtos.IssueBanRequest;
import net.vanfek.proxybans.service.api.v1.BanDtos.LiftBanRequest;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.service.BanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bans")
@Validated
@Tag(name = "Bans", description = "Issuing, lifting and reading bans")
class BansController {

    private final BanService bans;

    BansController(BanService bans) {
        this.bans = bans;
    }

    @GetMapping
    @Operation(summary = "List currently active bans, newest first")
    Mono<ActiveBansListResponse> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        Paging paging = new Paging(limit, offset);

        return this.bans.listActive(paging.limit(), paging.offset())
                .map(ActiveBanResponse::from)
                .collectList()
                .map(ActiveBansListResponse::new);
    }

    @GetMapping("/{uuid}/active")
    @Operation(summary = "The ban currently in force for a player, or 404")
    Mono<ResponseEntity<ActiveBanResponse>> active(@PathVariable UUID uuid) {
        return this.bans.activeBanFor(uuid)
                .map(active -> active
                        .map(ActiveBanResponse::from)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @GetMapping("/{uuid}/history")
    @Operation(summary = "Punishment log for a player, newest first")
    Mono<HistoryResponse> history(
            @PathVariable UUID uuid,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        Paging paging = new Paging(limit, offset);

        return this.bans.history(uuid, paging.limit(), paging.offset())
                .map(HistoryEntryResponse::from)
                .collectList()
                .map(HistoryResponse::new);
    }

    @PostMapping
    @Operation(summary = "Issue a ban; omit durationSeconds for a permanent one")
    Mono<ResponseEntity<ActiveBanResponse>> issue(@Valid @RequestBody IssueBanRequest request) {
        Optional<Duration> duration = Optional.ofNullable(request.durationSeconds()).map(Duration::ofSeconds);

        IssueBanCommand command = new IssueBanCommand(
                request.playerUuid(),
                request.issuedByUuid(),
                request.issuedByName(),
                request.reason(),
                duration
        );

        return this.bans.issue(command)
                .map(ActiveBanResponse::from)
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @DeleteMapping("/{uuid}")
    @Operation(summary = "Lift the active ban for a player")
    Mono<ResponseEntity<Void>> lift(@PathVariable UUID uuid, @Valid @RequestBody LiftBanRequest request) {
        LiftBanCommand command = new LiftBanCommand(
                uuid,
                request.liftedByUuid(),
                request.liftedByName(),
                Optional.ofNullable(request.reason()).orElse("")
        );

        return this.bans.lift(command).thenReturn(ResponseEntity.noContent().build());
    }

}
