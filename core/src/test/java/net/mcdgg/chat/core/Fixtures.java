package net.mcdgg.chat.core;

/**
 * A subset of destiny.gg's published flair list, kept in its real catalogue order.
 *
 * <p>Order matters: {@code usernameColorFlair} filters the catalogue rather than the user's
 * features, so which of two flairs at the same priority wins is decided by which appears
 * first here. The entries chosen are the ones that actually collide — four coloured flairs
 * at priority 3, two pairs at 4 and 6, two at 1 — plus {@code flair125}, whose image entry
 * publishes null for every field.
 *
 * <p>Held as a fixture rather than a checked-in copy of {@code flairs.json}, so the build
 * neither redistributes destiny.gg's data nor needs the network to run its tests.
 */
final class Fixtures {

    private Fixtures() {}

    static final String FLAIRS_JSON = """
            [
              {"label":"Micro","name":"flair17","hidden":true,"priority":1,
               "color":"#FCE205","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/micro.png","mime":"image/png","height":18,"width":20}]},
              {"label":"Admin","name":"admin","hidden":true,"priority":1,
               "color":"#EE1F1F","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/admin.png","mime":"image/png","height":16,"width":16}]},
              {"label":"Tier 5","name":"flair33","hidden":false,"priority":3,
               "color":"#eb79da","rainbowColor":true,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t5.png","mime":"image/png","height":19,"width":18}]},
              {"label":"Tier 5 Alt","name":"flair42","hidden":true,"priority":3,
               "color":"#eb79da","rainbowColor":true,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t5alt.png","mime":"image/png","height":18,"width":18}]},
              {"label":"Tier 4","name":"flair7","hidden":false,"priority":3,
               "color":"#FC4C02","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t4.png","mime":"image/png","height":18,"width":13}]},
              {"label":"Contributor","name":"flair12","hidden":false,"priority":3,
               "color":"#E79015","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/contrib.png","mime":"image/png","height":16,"width":16}]},
              {"label":"Tier 3","name":"flair26","hidden":false,"priority":4,
               "color":"#DD29D2","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t3.png","mime":"image/png","height":19,"width":18}]},
              {"label":"Tier 3 Alt","name":"flair8","hidden":true,"priority":4,
               "color":"#DD29D2","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t3alt.png","mime":"image/png","height":18,"width":18}]},
              {"label":"Tier 2","name":"flair22","hidden":false,"priority":6,
               "color":"#2ADDC8","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t2.png","mime":"image/png","height":19,"width":18}]},
              {"label":"Tier 2 Alt","name":"flair1","hidden":true,"priority":6,
               "color":"#2ADDC8","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t2alt.png","mime":"image/png","height":18,"width":18}]},
              {"label":"Tier 1","name":"flair13","hidden":true,"priority":7,
               "color":"#59AEEA","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/t1.png","mime":"image/png","height":18,"width":18}]},
              {"label":"Subscriber","name":"subscriber","hidden":true,"priority":9,
               "color":"#59AEEA","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/subscriber.png","mime":"image/png","height":18,"width":18}]},
              {"label":"Head Mod","name":"flair125","hidden":true,"priority":126,
               "color":"#ffd88c","rainbowColor":false,
               "image":[{"url":null,"name":null,"mime":null,"height":null,"width":null}]},
              {"label":"Moderator","name":"moderator","hidden":true,"priority":127,
               "color":"#DB4C1C","rainbowColor":false,
               "image":[{"url":"https://cdn.destiny.gg/flairs/moderator.png","mime":"image/png","height":16,"width":16}]}
            ]
            """;

    /**
     * The shape of the real {@code flairs.css}: {@code order} only on visible flairs,
     * {@code display: none} on the rest, and a {@code .user.<name>} colour rule that must
     * not be mistaken for a flair rule.
     */
    static final String FLAIRS_CSS = """
            .flair.moderator { display: none !important; }
            .user.moderator { color: #DB4C1C; }
            .flair.flair8 { display: none !important; }
            .flair.flair33 { background-image: url("t5.png"); height: 19px; width: 18px; order: 3; }
            .flair.flair7 { background-image: url("t4.png"); height: 18px; width: 13px; order: 3; }
            .flair.flair12 { background-image: url("contrib.png"); height: 16px; width: 16px; order: 3; }
            .flair.flair26 { background-image: url("t3.png"); height: 19px; width: 18px; order: 4; }
            .flair.flair22 { background-image: url("t2.png"); height: 19px; width: 18px; order: 6; }
            .flair.flair17 { display: none !important; }
            .flair.admin { display: none !important; }
            .flair.flair42 { display: none !important; }
            .flair.flair1 { display: none !important; }
            .flair.flair13 { display: none !important; }
            .flair.subscriber { display: none !important; }
            .flair.flair125 { display: none !important; }
            """;

    static FlairCatalogue flairs() {
        return FlairCatalogue.parse(FLAIRS_JSON, FLAIRS_CSS);
    }
}
