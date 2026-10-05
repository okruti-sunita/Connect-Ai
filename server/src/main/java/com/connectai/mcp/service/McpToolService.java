package com.connectai.mcp.service;

import com.connectai.mcp.api.dto.RegisterMcpServerRequest;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpServer;
import com.connectai.mcp.operation.McpToolOperations;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpToolService implements McpToolOperations {

    private final McpServerRepository repository;
    private final SecretEncryptionService encryptionService;

    @Override
    @Transactional
    public McpServer registerTool(RegisterMcpServerRequest request) {
        String name = request.name().trim();
        String endpoint = request.endpoint().trim();

        log.info(
                "Registering MCP server: name={}, transport={}, authType={}, enabled={}",
                name,
                request.transport(),
                request.authType(),
                request.enabled() == null || request.enabled()
        );

        String encryptedSecret = request.secret() == null || request.secret().isBlank()
                ? null
                : encryptionService.encrypt(request.secret());

        EMcpServer entity = new EMcpServer(
                name,
                request.transport(),
                endpoint,
                request.authType(),
                encryptedSecret,
                request.enabled() == null || request.enabled()
        );

        EMcpServer savedEntity = repository.save(entity);

        log.info(
                "MCP server registered: id={}, name={}, status={}",
                savedEntity.getId(),
                savedEntity.getName(),
                savedEntity.getStatus()
        );

        return McpServer.from(savedEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServer> getTools() {
        return repository.findAll()
                .stream()
                .map(McpServer::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public McpServer getTool(UUID id) {
        return repository.findById(id)
                .map(McpServer::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP server " + id + " not found"
                ));
    }
}
