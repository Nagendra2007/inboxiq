package com.mailroom.controller;

import com.mailroom.security.SpaRoutes;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * When the React build is bundled into the jar (the root Dockerfile does
 * this), a hard refresh or deep link on a client-side route such as
 * {@code /inbox} reaches the server. Forward those to the SPA shell so React
 * Router can take over. The paths come from {@link SpaRoutes}, which is the
 * single list; a test checks it against the routes in
 * {@code frontend/src/App.tsx}.
 *
 * In local development the Vite dev server serves the frontend and this
 * controller is never hit.
 */
@Controller
public class SpaForwardController {

    @GetMapping({SpaRoutes.ROOT, SpaRoutes.LOGIN, SpaRoutes.INBOX, SpaRoutes.ASK,
            SpaRoutes.DASHBOARD, SpaRoutes.ACTION_ITEMS, SpaRoutes.SETTINGS})
    public String forwardToSpa() {
        return "forward:/index.html";
    }
}
