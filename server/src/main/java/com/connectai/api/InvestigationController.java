package com.connectai.api;

import com.connectai.api.dto.CreateInvestigationRequest;
import com.connectai.api.dto.EvidenceResponse;
import com.connectai.api.dto.InvestigationResponse;
import com.connectai.api.dto.InvestigationSummary;
import com.connectai.api.dto.PageResponse;
import com.connectai.domain.InvestigationStatus;
import com.connectai.service.InvestigationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Thin HTTP layer: translates requests into service calls, nothing more. */
@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InvestigationResponse> create(@Valid @RequestBody CreateInvestigationRequest request) {
        InvestigationResponse created = service.create(request.question());
        return ResponseEntity.created(URI.create("/api/investigations/" + created.id())).body(created);
    }

    /** Example: GET /api/investigations?status=ANSWERED&archived=false&q=payment&page=0&size=20 */
    @GetMapping
    public PageResponse<InvestigationSummary> list(
            @RequestParam(required = false) InvestigationStatus status,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(status, archived, q, page, size);
    }

    @GetMapping("/{id}")
    public InvestigationResponse get(@PathVariable String id) {
        return service.get(UUID.fromString(id));
    }

    @GetMapping("/{id}/evidence")
    public List<EvidenceResponse> evidence(@PathVariable String id) {
        return service.getEvidence(UUID.fromString(id));
    }
}
