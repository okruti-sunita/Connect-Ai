package com.connectai.mcp.repository;

import com.connectai.mcp.entity.EMcpServer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<EMcpServer, Long> {
}
