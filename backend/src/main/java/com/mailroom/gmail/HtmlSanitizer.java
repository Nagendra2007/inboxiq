package com.mailroom.gmail;

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
 * {@code <script>}, event handler attributes (onclick, onerror, ...), and
 * {@code javascript:} URLs.
 *
 * <p>The presentational attributes below look archaic, and they are: email
 * HTML is still written for 1999 renderers, so a newsletter's entire design
 * lives in {@code bgcolor}, {@code cellpadding}, {@code align} and nested
 * tables. Stripping them doesn't make the mail safer, it just makes it look
 * like a text file with links in it.
 *
 * <p>What is deliberately NOT handled here is remote loading. Images, and
 * the {@code url()} in a style rule, are tracking pixels as much as they are
 * decoration, and an allowlist is the wrong tool for stopping them: this
 * class used to strip {@code <img>} outright and still let
 * {@code style="background-image:url(...)"} through, which is the same leak
 * wearing a different hat. The frontend renders this in a sandboxed iframe
 * and governs every remote load with one Content-Security-Policy, which is
 * a thing browsers enforce and a safelist can only approximate.
 */
@Component
public class HtmlSanitizer {

    private final Safelist safelist;

    public HtmlSanitizer() {
        this.safelist = Safelist.relaxed()
                .addTags("hr", "center", "font", "style")
                .addAttributes(":all", "style", "class", "align", "valign", "bgcolor", "width", "height", "dir")
                .addAttributes("table", "border", "cellpadding", "cellspacing", "background")
                .addAttributes("td", "background", "nowrap")
                .addAttributes("th", "background", "nowrap")
                .addAttributes("tr", "background")
                .addAttributes("font", "color", "face", "size")
                .addProtocols("a", "href", "http", "https", "mailto")
                // data: is an embedded image, not a callback to the sender.
                .addProtocols("img", "src", "http", "https", "data");
    }

    public String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) return rawHtml;
        String cleaned = Jsoup.clean(rawHtml, safelist);
        // Every remaining outbound link opens in a new tab without handing the
        // sender's page a reference back to ours (reverse tabnabbing).
        return cleaned.replace("<a ", "<a rel=\"noopener noreferrer\" target=\"_blank\" ");
    }
}
