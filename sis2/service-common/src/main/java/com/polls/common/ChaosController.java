package com.polls.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET/PUT /admin/chaos on the internal REST port, e.g.
 * {"latency_ms":150,"latency_rate":0.05,"fail_rate":0,"fail_after_rate":0}
 */
@RestController
@RequestMapping("/admin/chaos")
public class ChaosController {
    private final Chaos chaos;

    public ChaosController(Chaos chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public Chaos.Settings get() {
        return chaos.get();
    }

    @PutMapping
    public Chaos.Settings put(@RequestBody Chaos.Settings next) {
        chaos.set(next);
        return next;
    }
}
