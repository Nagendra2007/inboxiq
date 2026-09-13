package com.inboxiq.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * When the React build is bundled into the jar (the root Dockerfile does
 * this), a hard refresh or deep link on a client-side route such as
 * {@code /inbox} reaches the server. Forward those to the SPA shell so React
 * Router can take over. Keep this list in sync with {@code SecurityConfig.SPA_ROUTES}
 * and the routes in {@code frontend/src/App.tsx}.
 *
 * In local development the Vite dev server serves the frontend and this
 * controller is never hit.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/inbox", "/dashboard", "/action-items", "/settings"})
    public String forwardToSpa() {
        return "forward:/index.html";
    }
}
