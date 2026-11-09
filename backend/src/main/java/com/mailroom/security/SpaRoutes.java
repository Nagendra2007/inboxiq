package com.mailroom.security;

/**
 * The paths React Router owns.
 *
 * <p>Listed once. They were listed three times — in the security config,
 * in the controller that forwards them, and in the test that checked them
 * — each with a comment asking the next person to keep them in sync.
 * Nobody did, and {@code /ask} shipped returning 403 on a reload and 500
 * behind that, because clicking through the app never asks the server for
 * these paths at all. Only a refresh or a shared link does, which is not
 * something anybody does while building a feature.
 *
 * <p>Each is its own constant because an annotation's value has to be a
 * compile-time constant, so {@code @GetMapping} cannot be handed the
 * array.
 */
public final class SpaRoutes {

    private SpaRoutes() {}

    public static final String ROOT = "/";
    public static final String LOGIN = "/login";
    public static final String INBOX = "/inbox";
    public static final String ASK = "/ask";
    public static final String DASHBOARD = "/dashboard";
    public static final String ACTION_ITEMS = "/action-items";
    public static final String SETTINGS = "/settings";

    public static final String[] ALL = {ROOT, LOGIN, INBOX, ASK, DASHBOARD, ACTION_ITEMS, SETTINGS};
}
