package net.mcdgg.chat.core;

/**
 * What it takes to be served by a CDN that is picky about who is asking.
 *
 * <p>No URLs here any more: the baker is the only thing that reads destiny.gg, and it holds
 * its own. The mod fetches one manifest from wherever it is configured to.
 */
public final class DggCdn {

    /**
     * cdn.destiny.gg answers 403 to user agents it does not recognise, including the one
     * {@link java.net.http.HttpClient} sends by default. It is presented on every fetch
     * rather than conditionally, so a deployment that does point the mod at destiny.gg
     * directly still works. This is not an attempt to hide anything.
     */
    public static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private DggCdn() {}
}
