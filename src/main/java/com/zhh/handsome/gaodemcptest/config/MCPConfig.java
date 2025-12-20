/*package com.zhh.handsome.gaodemcptest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class MCPConfig {

    @Bean
    public List<NamedClientMcpTransport> mcpClientTransport(){
        HttpClientSseClientTransport build = HttpClientSseClientTransport.builder("http://mcp.amap.com")
                .sseEndpoint("/sse?key=467ab90b800b99df269b771674590664")
                .objectMapper(new ObjectMapper())
                .build();
        return List.of(new NamedClientMcpTransport("amap", build));
    }
}*/
