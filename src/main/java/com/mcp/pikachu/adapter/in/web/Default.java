package com.mcp.pikachu.adapter.in.web;

import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin
@Log4j2
public class Default {

    @GetMapping("/ping")
    public String ping() {
        log.info("ping...pong");
        return "Pong";
    }
}
