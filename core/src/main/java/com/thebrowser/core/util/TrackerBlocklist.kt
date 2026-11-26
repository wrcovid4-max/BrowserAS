package com.thebrowser.core.util

/** Small built-in list of well-known third-party tracking hosts. Basic protection, not a full filter engine. */
object TrackerBlocklist {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "facebook.net",
        "connect.facebook.net", "graph.facebook.com", "pixel.facebook.com", "analytics.twitter.com",
        "ads-twitter.com", "static.ads-twitter.com", "ads.linkedin.com", "px.ads.linkedin.com",
        "bat.bing.com", "clarity.ms", "hotjar.com", "static.hotjar.com", "script.hotjar.com",
        "mixpanel.com", "api.mixpanel.com", "segment.io", "segment.com", "amplitude.com",
        "fullstory.com", "mouseflow.com", "crazyegg.com", "optimizely.com", "newrelic.com",
        "scorecardresearch.com", "quantserve.com", "outbrain.com", "taboola.com", "criteo.com",
        "criteo.net", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "moatads.com", "adsrvr.org", "demdex.net", "everesttech.net", "krxd.net", "bluekai.com",
        "chartbeat.com", "chartbeat.net", "branch.io", "app-measurement.com", "adjust.com",
        "appsflyer.com", "tiktok.com/analytics", "analytics.tiktok.com", "snap.licdn.com",
    ).filter { !it.contains('/') }.toSet()

    fun isTracker(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }
}
