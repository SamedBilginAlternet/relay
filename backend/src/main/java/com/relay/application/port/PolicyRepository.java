package com.relay.application.port;

import com.relay.domain.ToolPolicy;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository {

    List<ToolPolicy> findAll();

    Optional<ToolPolicy> findByToolName(String toolName);

    ToolPolicy save(ToolPolicy policy);
}
