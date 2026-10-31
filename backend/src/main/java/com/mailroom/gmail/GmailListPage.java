package com.mailroom.gmail;

import java.util.List;

public record GmailListPage(List<String> messageIds, String nextPageToken) {}
