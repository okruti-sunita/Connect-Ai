package com.connectai.mcp.resource;

import com.connectai.mcp.api.dto.CreateMcpConnectionRequest;
import com.connectai.mcp.api.dto.CreatedMcpConnectionResponse;
import com.connectai.mcp.api.dto.McpConnectionTestResponse;
import com.connectai.mcp.service.CatalogConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/connections")
public class McpConnectionResource {
    private final CatalogConnectionService connections;

    @PostMapping("/test")
    public ResponseEntity<McpConnectionTestResponse> test(@Valid @RequestBody CreateMcpConnectionRequest request) {
        McpConnectionTestResponse result = connections.test(request);
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(result);
    }

    @PostMapping
    public ResponseEntity<CreatedMcpConnectionResponse> create(@Valid @RequestBody CreateMcpConnectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(connections.create(request));
    }
}
