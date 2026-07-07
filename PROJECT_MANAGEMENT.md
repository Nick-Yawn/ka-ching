# Project Management — Ka-Ching!

A RuneLite plugin that displays the GE price of everything you consume in-game
(spells, ammo, weapon charges, cannonballs, food, potions, bones) with a coin jingle.

- **Type:** RuneLite plugin (Java, Gradle)
- **Distribution:** RuneLite Plugin Hub
- **License:** see [LICENSE](LICENSE)
- **Authors:** nick-yawn, tyuandev, luke-skynet

## Status

Shipped v1. Core accounting features are implemented and covered by a test suite + CI.

## Roadmap

### Now / In progress
- [ ] _(add current tasks here)_

### Next / Backlog
- [ ] Session totals — clearable/toggleable running tally (top launch-day request on Reddit)
- [ ] Overlay customization: font color, size, placement (harmless, commonly requested)
- [ ] Wilderness weapons — revenant ether weapons (Craw's/Webweaver, Viggora's/Ursine,
      Thammaron's/Accursed): 1 revenant ether per attack, same detection as other charged weapons
- [ ] Blighted spell sacks — casting from a sack consumes no runes today (silent);
      bill the sack's GE price instead
- [ ] Verify Plugin Hub submission / listing metadata is current
- [ ] Expand test coverage for edge cases (Ava's recovery tiers, recharge detection)

### Later / Ideas
- [ ] Loss on death — bill what you just lost (fees, unprotected items). Maximum spirit-of-the-plugin
- [ ] Ironman mode — GE prices mean nothing to irons; alternative valuation
      (e.g. time-to-reacquire: "minutes of your life lost", per the Reddit thread)
- [ ] Per-activity breakdown or export
- [ ] Additional charged weapons / consumables as OSRS adds them
- [ ] Bone Offering option

## Done
- [x] v1 release: overhead GE pricing for spells, ammo, charged weapons, cannon
- [x] Food & potion per-dose pricing (pro-rated multi-bite foods, dose step-down)
- [x] Buried-bones pricing
- [x] Accounting test suite + CI
- [x] Demo GIF and README

## Known considerations
Intentional design decisions — not bugs or TODOs:
- Container residues (jugs, bowls, pie dishes) intentionally never credited — this is a
  negative-only plugin.
- Ammo caught by Ava's or landing on the ground is intentionally not tallied.
- Staff-provided runes are not priced (they're supplied free, so they correctly cost 0 gp).

## Codebase map
| File | Responsibility |
|------|----------------|
| [KaChingPlugin.java](src/main/java/com/kaching/KaChingPlugin.java) | Main plugin: event handling, diffing, accounting |
| [KaChingConfig.java](src/main/java/com/kaching/KaChingConfig.java) | User-facing config toggles |
| [KaChingOverlay.java](src/main/java/com/kaching/KaChingOverlay.java) | Floating overhead price text rendering |
| [ChargedWeapon.java](src/main/java/com/kaching/ChargedWeapon.java) | Per-charge recipe definitions for charged weapons |
| [KaChingAccountingTest.java](src/test/java/com/kaching/KaChingAccountingTest.java) | Accounting logic tests |
| [KaChingPluginTest.java](src/test/java/com/kaching/KaChingPluginTest.java) | Dev launcher (sideloads the plugin into a local client; not a test) |

## Development
- Build: `./gradlew build`
- Test: `./gradlew test`
- Local run/test: `./test-plugin.sh`

## Contributing
Work happens via PRs against `main`. See recent history for the review-driven style
(small, focused commits; accounting correctness emphasized).
