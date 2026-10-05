package com.connectai.mcp.repository;

import com.connectai.mcp.entity.EMcpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface McpServerRepository extends JpaRepository<EMcpServer, UUID> {
}
