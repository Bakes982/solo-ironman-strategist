# Solo Ironman Strategist

A RuneLite plugin that delivers contextual, data-driven intelligence for solo Ironmen.
Built around the **2026 meta** — Varlamore, Sailing, Hunter Rumours, Moonlight Moth Mixes, Scorching Bow, and more.

> **Source**: [github.com/bakes982/solo-ironman-strategist](https://github.com/bakes982/solo-ironman-strategist)

---

## Features at a Glance

| Tab | What it does |
|-----|-------------|
| **GUIDE** | Live metric cards + milestone checklist + daily tasks + farm timer + death tracker |
| **BANK**  | Multi-skill bank analysis — Herblore, Cooking, Crafting, Construction, Fletching, Smithing, Farming, Prayer, Alch Analyzer, Shipyard |
| **SKILLS**| Training method guide — 23 skills, auto-selects the skill relevant to your next milestone on login |
| **TIPS**  | 2026 meta efficiency tips + Money Making section (filtered by your levels + quest completions) |

---

## Guide Tab

### Live Metric Cards (update every game tick)
| Card | Description |
|------|-------------|
| **NEXT MILESTONE** | The highest-priority incomplete progression step from the 24-item milestone checklist |
| **SUPPLY FORECAST** | Prayer Potion count + estimated hours of Slayer tasks remaining; red < 1 hr, amber < 4 hrs, green ≥ 4 hrs |
| **ACTIVE RUMOUR** | Current Hunter Guild Rumour target with trap type and teleport suggestion |
| **ACTIVE GOAL** | Your selected end-game gear goal (Dragon Defender → Twisted Bow, 17 options) |
| **SLAYER TASK** | Live task name and kill count from VarPlayers 386/387 |
| **READY TO PROCESS** | Bank analysis summary — highest-priority skill action waiting when you open your bank |
| **LAST DEATH** | Location (X/Y coordinates) + live despawn countdown (amber → red under 10 min) |

### Progression Checklist
- 24 milestones ordered by priority (Pandemonium → Sailing 70 → Scorching Bow)
- **Auto-completion**: quest state, item in bank/equipped, or skill level reached
- Manual checkbox override for edge cases
- "+N more" overflow cap keeps the list focused at 5 visible items

### Daily Tasks
Four Ironman-specific daily tasks with UTC midnight auto-reset:
- Buy Battlestaves (Zaff / Naff)
- Birdhouse Run (Fossil Island)
- Herb Run + Farming Contracts
- Collect Kingdom Resources (Miscellania)

### Farm Run Timer
80-minute countdown (accurate herb growth cycle), persisted in config across restarts.
Starts on button click; resets automatically.

---

## Bank Tab

Opens automatically when you access your bank.  Rebuilds all sections on every bank change.

### Herblore
10 tracked potions (Strength → Ranging Potion).  Each card shows:
- **Red**: herb present, secondary completely missing → expand to see buylist
- **Amber**: partial secondaries → craftable count + shortfall
- **Green**: ready to craft → total XP banked

### Cooking
Raw fish by XP (Anglerfish → Trout), with total banked XP projection (Lvl N → M).

### Crafting
- **Dragonhide bodies** — Green/Blue/Red/Black tanned leather (3 leathers per body); level-gated XP cards
- **Battlestaves (orb)** — Water/Earth/Fire/Air orb × battlestaff; XP projection (lvl 54–66)
- Gem cutting: Zenyte → Sapphire (XP per gem)
- Glass blowing: Molten Glass → Unpowered Orb
- Flax spinning: Flax → Bowstring (15 XP each)
- Combined XP projection summary (Lvl N → M) across all Crafting sub-categories

### Construction
Oak / Teak / Mahogany / Regular planks with efficient build-method XP.

### Fletching
- Logs → Longbow (u): 7 tiers (Normal through Redwood), level-gated display
- Broad Arrows + Broad Bolts from the Slayer shop

### Farming
9 herb seeds with estimated XP (planting + avg 5 picks per seed).

### Smithing
Steel bars → cannonball production card (shows bars → balls count, Smithing XP, ~task minutes at 2 balls/sec).
Mithril / Adamant / Rune bars with platelegs XP reference and Giant's Foundry tip. Hidden when no bars banked.

### Prayer
- **Bones (Chaos Altar)** — 8 bone types (Baby Dragon → Superior Dragon), XP shown at 3.5× base (Chaos Altar rate). Note: 50% chance the bone is saved per use.
- **Ensouled Heads (Arceuus reanimation)** — Abyssal (1,600 XP, Magic 90), Dragon (1,560 XP, Magic 93), Demon (1,560 XP, Magic 84), Dagannoth (1,560 XP, Magic 72)
- Combined XP projection summary (Lvl N → M)

### Alch Analyzer
Scans 31 tracked items (rune/dragon weapons, crafted bows) and highlights any where
**HA value > GE price**, sorted by profit per item descending.

### Shipyard
Ship hull/sail materials for Sailing upgrade readiness:
- **Sloop** (50 planks + 100 steel nails + 2 cloth)
- **Caravel** (100 oak + 200 steel nails + 5 cloth)
- **Galleon** (200 teak + 500 mithril nails + 10 cloth)

Cards are green when all materials are ready, amber when short (shows exact shortfall).

---

## Skills Tab

Dropdown covering 23 skills + Sailing.  Each skill shows:
- **XP-to-next-level info bar**: `Level 75  ·  42K xp to 76  [68%]`
- Current best training method (auto-expanded) and next method to unlock
- Level requirements and XP rates per method, with Ironman-specific tips
- Auto-selects the skill relevant to your next milestone on login

---

## Tips Tab

### Meta Efficiency Tips
24 tips gated by skill level — only shows tips relevant to your current progression.
Covers: Sailing routes, Hunter Rumours, Moonlight Moth Mixes, Farming, Agility, Thieving, Slayer, and more.

### Money Making Methods
15 methods (H.A.M. Storeroom → Vorkath) showing GP/hr estimates, filtered live by:
- All skill requirements met
- All required quests completed (quest state checked via RuneLite API)

Sorted by GP/hr descending.  Each card shows a requirements line:
`Reqs: RC 77, Mine 38, Craft 38, Agil 73` with colour-coded met/unmet chips.

---

## Overlays

| Overlay | Trigger | Content |
|---------|---------|---------|
| **Shop Overlay** | Shop interface open | Highlights items worth buying (scales to your bank scan) |
| **Active Grind Overlay** | In-game HUD | Next milestone + current supply level; shows calibration hint before first bank scan |

---

## Configuration

| Section | Setting | Default | Description |
|---------|---------|---------|-------------|
| Active Grinds | **Active Goal** | BotFA (BOWFA) | Your current gear target (17 options: Fire Cape → Infernal Cape) |
| Active Grinds | **Low Supply Alert (Hours)** | 1.0 | Supply overlay turns red below this many hours of prayer potions |
| Shop Overlay | **Broad Arrowheads** | 5000 | Highlight in shop if bank < this; red if < 20% of threshold |
| Shop Overlay | **Nature Runes** | 1000 | Highlight in shop if bank < this; red if < 20% of threshold |
| Shop Overlay | **Rosewood Planks** | 200 | Highlight in shop if Sailing ≥ 40 and bank < this |
| Bank Warnings | **Herblore Secondary Warning** | On | Warn when you have herbs but are missing matching secondaries |
| Sailing | **Sailing Level** | 1 | Manual level input (Sailing not yet a native RuneLite tracked skill) |

---

## 2026 Meta Coverage

This plugin is designed around the **April 2026 OSRS meta**:

- **Sailing** skill (1–99 via manual level config), all three ship tiers tracked
- **Pandemonium** quest → Starter Sloop (priority 0 milestone, day-1 meta)
- **Varlamore** content: Hunter Guild Rumours, Moonlight Moths (prayer solution), Sunlight Crossbow (Fletching 74), Colossal Wyrm Agility course
- **Scorching Bow** — Araxxor (Slayer 92), Synapse + Magic Shortbow, Fletching 85
- **Zombie Axe** — Defender of Varrock quest, BIS bridge melee weapon
- **Hunter Rumours** — per-creature trap type + teleport suggestions in the ACTIVE RUMOUR card

---

## Installation (Developer / Sideload)

```bash
git clone https://github.com/bakes982/solo-ironman-strategist
cd solo-ironman-strategist
./gradlew build
```

Then in RuneLite: **RuneLite Settings → Developer Mode → Sideload Plugin** and point to the built JAR in `build/libs/`.

Or run directly for testing:
```bash
./gradlew runClient
```

---

## Project Structure

```
src/main/java/com/soloironman/
  SoloIronmanPlugin.java      — entry point, EventBus subscribers
  SoloIronmanPanel.java       — tab orchestrator
  GuideTabPanel.java          — Guide tab (metrics, checklist, daily, death tracker)
  BankTabPanel.java           — Bank analysis (11 skill sections + Alch Analyzer + Shipyard)
  SkillsTabPanel.java         — Skill training guide
  TipsTabPanel.java           — Meta tips + money making
  BankScanner.java            — Bank/equipment cache (HashMap, O(1) lookup)
  MetaDataProvider.java       — Loads ironman_meta.json (24 milestones, 15 money methods)
  SkillGuideProvider.java     — Loads skill_guides.json (23 skills)
  ProgressionService.java     — Next milestone logic
  SupplyService.java          — Prayer pot forecast
  RumourService.java          — Hunter Rumour info (VarPlayer 4120, 15 IDs)
  GoalService.java            — Active goal completion check
  DeathTrackerService.java    — Death location + despawn countdown
  StrategistCard.java         — Reusable expandable card component
  ShopOverlay.java            — Shop item highlighting
  ActiveGrindOverlay.java     — On-screen HUD

src/main/resources/com/soloironman/
  ironman_meta.json           — 24 milestones, 24 tips, 15 money methods, sailing meta
  skill_guides.json           — 23 skills, training methods
```

---

## Requirements

- RuneLite 1.12.22+
- Java 11+ (Java 21 recommended for development)
- OSRS account — designed for Ironman mode but works on any account

---

## License

Open source. See LICENSE for details.
