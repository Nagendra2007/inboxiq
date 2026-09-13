package com.inboxiq.gmail;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Sanitizes email HTML bodies before they are stored/returned to the
 * frontend. Email HTML is one of the most hostile inputs an application can
 * render: it is entirely attacker-controlled and commonly carries
 * script/event-handler-based XSS payloads. We never trust it.
 *
 * Strategy: an allowlist (not a denylist) of formatting tags/attributes —
 * anything not explicitly allowed is stripped, including all
 * {@code <script>}, {@code <style>} (inline style is allowed on a narrow tag
 * set only), event handler attributes (onclick, onerror, ...), and
 * {@code javascript:}/{@code data:} URLs.
 */
@Component
public class HtmlSanitizer {

    private final Safelist safelist;

    public HtmlSanitizer() {
        this.safelist = Safelist.relaxed()
                .addTags("hr")
                .addAttributes(":all", "style")
                .addProtocols("a", "href", "http", "https", "mailto")
                .removeProtocols("img", "src", "data")
                .addProtocols("img", "src", "http", "https")
                // Never allow email HTML to auto-load remote images by default;
                // the frontend can offer an explicit "load images" action later.
                .removeTags("img");
    }

    public String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) return rawHtml;
        String cleaned = Jsoup.clean(rawHtml, safelist);
        // Every remaining outbound link opens in a new tab without handing the
        // sender's page a reference back to ours (reverse tabnabbing).
        return cleaned.replace("<a ", "<a rel=\"noopener noreferrer\" target=\"_blank\" ");
    }
}
