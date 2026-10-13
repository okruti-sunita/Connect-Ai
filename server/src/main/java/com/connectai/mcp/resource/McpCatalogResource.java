package com.connectai.mcp.resource;

import com.connectai.mcp.catalog.McpCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/catalog")
public class McpCatalogResource {
    private final McpCatalogService catalog;

    @GetMapping
    public List<McpCatalogService.CatalogItemView> list() {
        return catalog.list();
    }
}
