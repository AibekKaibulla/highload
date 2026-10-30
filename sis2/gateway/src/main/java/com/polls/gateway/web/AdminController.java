package com.polls.gateway.web;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.gateway.auth.Auth;
import com.polls.gateway.resilience.GatewaySettings;
import com.polls.gateway.resilience.SettingsHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dev/experiment endpoints. Enabled only with auth.dev-endpoints=true (never in production).
 *  GET  /admin/settings          current protocol, deadline and retry policy
 *  PUT  /admin/settings          replace them (switch grpc/rest, change deadline...) without restart
 *  GET  /dev/token?user_id=1     a test JWT
 */
@RestController
@ConditionalOnProperty(name = "auth.dev-endpoints", havingValue = "true")
public class AdminController {
    private final SettingsHolder settings;
    private final Auth auth;

    public AdminController(SettingsHolder settings, Auth auth) {
        this.settings = settings;
        this.auth = auth;
    }

    @GetMapping("/admin/settings")
    public GatewaySettings get() {
        return settings.get();
    }

    @PutMapping("/admin/settings")
    public GatewaySettings put(@RequestBody GatewaySettings next) {
        settings.set(next);
        return next;
    }

    @GetMapping("/dev/token")
    public Map<String, String> token(@RequestParam(name = "user_id") long userId) {
        if (userId <= 0) throw new ApiException(ErrorCode.INVALID_ARGUMENT, "user_id must be positive");
        return Map.of("token", auth.issue(userId, 24 * 3600));
    }
}
