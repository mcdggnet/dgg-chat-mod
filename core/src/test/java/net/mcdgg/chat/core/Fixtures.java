package net.mcdgg.chat.core;

/**
 * A subset of destiny.gg's flair list, in its real catalogue order, in the shape the baker
 * emits.
 *
 * <p>Deliberately the baked shape rather than raw {@code flairs.json}: that is the only form
 * the client ever sees, so a fixture in any other shape would be testing a code path nobody
 * runs. An earlier version did exactly that, and a reader following the raw-JSON parser
 * reasonably concluded flair icons could never render.
 *
 * <p>Order matters: {@code usernameColorFlair} filters the catalogue rather than the user's
 * features, so which of two flairs at the same priority wins is decided by which appears
 * first here. The entries chosen are the ones that actually collide — four coloured flairs
 * at priority 3, two of them rainbow, plus pairs at 1, 4 and 6 — together with the two real
 * edge cases: {@code flair125}, which publishes no usable image, and {@code flair5}, which
 * publishes an empty colour string.
 *
 * <p>Held as a fixture rather than a checked-in copy of destiny.gg's data, so the build
 * neither redistributes it nor needs the network to run its tests.
 */
final class Fixtures {

    private Fixtures() {}

    static final String BASE_URL = "https://mcdgg.net/dggchat/manifest.json";

    static final String MANIFEST = """
            {
              "version": 1,
              "emotes": {},
              "flairs": [
                {"name":"flair17","label":"Micro","hidden":true,"priority":1,
                 "color":"#FCE205","rainbowColor":false,"order":2147483647,
                 "iconFile":"flair-flair17.aaa.png","iconWidth":20,"iconHeight":18},
                {"name":"admin","label":"Admin","hidden":true,"priority":1,
                 "color":"#EE1F1F","rainbowColor":false,"order":2147483647},
                {"name":"flair33","label":"Tier 5","hidden":false,"priority":3,
                 "color":"#eb79da","rainbowColor":true,"order":3,
                 "iconFile":"flair-flair33.bbb.png","iconWidth":18,"iconHeight":19},
                {"name":"flair42","label":"Tier 5 Alt","hidden":true,"priority":3,
                 "color":"#eb79da","rainbowColor":true,"order":2147483647},
                {"name":"flair7","label":"Tier 4","hidden":false,"priority":3,
                 "color":"#FC4C02","rainbowColor":false,"order":3,
                 "iconFile":"flair-flair7.ccc.png","iconWidth":13,"iconHeight":18},
                {"name":"flair12","label":"Contributor","hidden":false,"priority":3,
                 "color":"#E79015","rainbowColor":false,"order":3,
                 "iconFile":"flair-flair12.ddd.png","iconWidth":16,"iconHeight":16},
                {"name":"flair26","label":"Tier 3","hidden":false,"priority":4,
                 "color":"#DD29D2","rainbowColor":false,"order":4,
                 "iconFile":"flair-flair26.eee.png","iconWidth":18,"iconHeight":19},
                {"name":"flair8","label":"Tier 3 Alt","hidden":true,"priority":4,
                 "color":"#DD29D2","rainbowColor":false,"order":2147483647},
                {"name":"flair22","label":"Tier 2","hidden":false,"priority":6,
                 "color":"#2ADDC8","rainbowColor":false,"order":6,
                 "iconFile":"flair-flair22.fff.png","iconWidth":18,"iconHeight":19},
                {"name":"flair1","label":"Tier 2 Alt","hidden":true,"priority":6,
                 "color":"#2ADDC8","rainbowColor":false,"order":2147483647},
                {"name":"flair13","label":"Tier 1","hidden":true,"priority":7,
                 "color":"#59AEEA","rainbowColor":false,"order":2147483647},
                {"name":"subscriber","label":"Subscriber","hidden":true,"priority":9,
                 "color":"#59AEEA","rainbowColor":false,"order":2147483647},
                {"name":"flair124","label":"Lore Master","hidden":false,"priority":50,
                 "color":"","rainbowColor":false,"order":50,
                 "iconFile":"flair-flair124.ggg.png","iconWidth":18,"iconHeight":18},
                {"name":"flair125","label":"Head Mod","hidden":true,"priority":126,
                 "color":"#ffd88c","rainbowColor":false,"order":2147483647},
                {"name":"moderator","label":"Moderator","hidden":true,"priority":127,
                 "color":"#DB4C1C","rainbowColor":false,"order":2147483647}
              ]
            }
            """;

    static FlairCatalogue flairs() {
        return BakeManifest.parse(MANIFEST, BASE_URL).flairs();
    }
}
