package net.vanfek.proxypunishments.service.api.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.ActivePunishmentResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.ActivePunishmentsListResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.HistoryEntryResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.HistoryResponse;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.IssuePunishmentRequest;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.IssueWarningRequest;
import net.vanfek.proxypunishments.service.api.v1.PunishmentDtos.LiftPunishmentRequest;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
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
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Punishments", description = "Issuing, lifting and reading bans, mutes and warnings")
class PunishmentsController {

    private final PunishmentService punishments;

    PunishmentsController(PunishmentService punishments) {
        this.punishments = punishments;
    }

    @GetMapping("/punishments")
    @Operation(summary = "List currently active punishments of one kind, newest first")
    Mono<ActivePunishmentsListResponse> list(
            @RequestParam(defaultValue = "BAN") PunishmentKind kind,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset
    ) {
        Paging paging = new Paging(limit, offset);

        return this.punishments.listActive(kind, paging.limit(), paging.offset())
                .map(ActivePunishmentResponse::from)
                .collectList()
                .map(ActivePunishmentsListResponse::new);
    }

    @GetMapping("/punishments/{uuid}/active")
    @Operation(summary = "The punishment of that kind currently in force for a player, or 404")
    Mono<ResponseEntity<ActivePunishmentResponse>> active(
            @PathVariable UUID uuid, @RequestParam(defaultValue = "BAN") PunishmentKind kind
    ) {
        return this.punishments.activePunishmentFor(uuid, kind)
                .map(active -> active
                        .map(ActivePunishmentResponse::from)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @GetMapping("/punishments/{uuid}/history")
    @Operation(summary = "Punishment log for a player, newest first")
    Mono<HistoryResponse> history(
            @PathVariable UUID uuid,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset
    ) {
        Paging paging = new Paging(limit, offset);

        return this.punishments.history(uuid, paging.limit(), paging.offset())
                .map(HistoryEntryResponse::from)
                .collectList()
                .map(HistoryResponse::new);
    }

    @PostMapping("/punishments")
    @Operation(summary = "Issue a ban or a mute; omit durationSeconds for a permanent one")
    Mono<ResponseEntity<ActivePunishmentResponse>> issue(@Valid @RequestBody IssuePunishmentRequest request) {
        Optional<Duration> duration = Optional.ofNullable(request.durationSeconds()).map(Duration::ofSeconds);

        IssuePunishmentCommand command = new IssuePunishmentCommand(
                request.playerUuid(), request.kind(),
                request.issuedByUuid(), request.issuedByName(),
                request.reason(), duration
        );

        return this.punishments.issue(command)
                .map(ActivePunishmentResponse::from)
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
    }

    @DeleteMapping("/punishments/{uuid}")
    @Operation(summary = "Lift the active punishment of that kind for a player")
    Mono<ResponseEntity<Void>> lift(
            @PathVariable UUID uuid, @RequestParam(defaultValue = "BAN") PunishmentKind kind,
            @Valid @RequestBody LiftPunishmentRequest request
    ) {
        LiftPunishmentCommand command = new LiftPunishmentCommand(
                uuid, kind,
                request.liftedByUuid(), request.liftedByName(),
                Optional.ofNullable(request.reason()).orElse("")
        );

        return this.punishments.lift(command).thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/warnings")
    @Operation(summary = "Record a warning; it lands in the log and never becomes an active punishment")
    Mono<ResponseEntity<HistoryEntryResponse>> warn(@Valid @RequestBody IssueWarningRequest request) {
        IssueWarningCommand command = new IssueWarningCommand(
                request.playerUuid(),
                request.issuedByUuid(), request.issuedByName(),
                request.reason()
        );

        return this.punishments.warn(command)
                .map(HistoryEntryResponse::from)
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
    }

}
