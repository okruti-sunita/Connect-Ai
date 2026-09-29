package com.connectai.api;

import com.connectai.api.dto.CreateInvestigationRequest;
import com.connectai.api.dto.EvidenceResponse;
import com.connectai.api.dto.InvestigationResponse;
import com.connectai.api.dto.InvestigationSummary;
import com.connectai.service.InvestigationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

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

    @GetMapping
    public List<InvestigationSummary> list() {
        return service.listRecent();
    }

    @GetMapping("/{id}")
    public InvestigationResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/evidence")
    public List<EvidenceResponse> evidence(@PathVariable Long id) {
        return service.getEvidence(id);
    }
}
