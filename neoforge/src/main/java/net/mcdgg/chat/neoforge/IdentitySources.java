package net.mcdgg.chat.neoforge;

import com.mojang.logging.LogUtils;
import net.mcdgg.chat.api.DggChatIdentity;
import net.mcdgg.chat.api.DggIdentitySource;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Discovers whatever is supplying DGG identities, if anything is.
 *
 * <p>Nothing here knows about {@code dggauth} specifically. NeoForge mods share a
 * classloader, so a {@link ServiceLoader} lookup finds implementations declared by any
 * installed mod, and finding none is a normal outcome rather than an error: the server
 * half simply stays dormant and clients render plain names.
 */
final class IdentitySources {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Resolved once. Mods are not installed or removed mid-run, so re-scanning per
     * lookup would buy nothing and cost a classpath walk on the server thread.
     */
    private static final List<DggIdentitySource> SOURCES = load();

    private IdentitySources() {}

    private static List<DggIdentitySource> load() {
        try {
            List<DggIdentitySource> found = StreamSupport
                    .stream(ServiceLoader.load(DggIdentitySource.class).spliterator(), false)
                    .sorted(Comparator.comparingInt(DggIdentitySource::priority).reversed())
                    .collect(Collectors.toList());
            found.forEach(s -> LOGGER.info("identity source: {}", s.getClass().getName()));
            return List.copyOf(found);
        } catch (ServiceConfigurationError | RuntimeException e) {
            // A broken provider in someone else's jar must not stop chat from loading.
            LOGGER.error("failed to load identity sources; names will render plain", e);
            return List.of();
        }
    }

    static int count() {
        return SOURCES.size();
    }

    /**
     * Hands the relay's change callback to every source. A source that cannot signal
     * keeps the interface's no-op default, and a source that throws here is skipped:
     * its mid-session changes then rely on the relay's own retry schedule, which is
     * the pre-signal behaviour, not a failure.
     */
    static void addChangeListener(Consumer<UUID> listener) {
        for (DggIdentitySource source : SOURCES) {
            try {
                source.addChangeListener(listener);
            } catch (RuntimeException e) {
                LOGGER.warn("identity source {} rejected the change listener; "
                        + "mid-session identity changes from it will wait on retries",
                        source.getClass().getName(), e);
            }
        }
    }

    /** First source with an answer, highest priority first; empty when none has one. */
    static Optional<DggChatIdentity> identityFor(UUID minecraftUuid) {
        for (DggIdentitySource source : SOURCES) {
            try {
                Optional<DggChatIdentity> identity = source.identityFor(minecraftUuid);
                if (identity.isPresent()) {
                    return identity;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("identity source {} threw; skipping it",
                        source.getClass().getName(), e);
            }
        }
        return Optional.empty();
    }
}
