package com.relay.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tool_policies")
public class ToolPolicyEntity {

    @Id
    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 16)
    private String mode;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
