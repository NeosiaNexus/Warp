# Strategie de construction communautaire pour Warp

**Date** : 31 mars 2026
**Objectif** : Plan complet pour batir une communaute open-source florissante autour de Warp, de 0 a 5000+ stars
**Sources** : GitHub Open Source Guides, CNCF Contributor Strategy, etudes de cas Minestom/Velocity/PaperMC, playbooks de croissance GitHub stars, guides Discord communautaire, CHAOSS metrics

---

## TABLE DES MATIERES

1. [Analyse des precedents MC](#1-analyse-des-precedents-mc)
2. [Positionnement vs Velocity](#2-positionnement-vs-velocity)
3. [Plan de croissance par phases](#3-plan-de-croissance-par-phases)
4. [Structure Discord](#4-structure-discord)
5. [Strategie premiers contributeurs](#5-strategie-premiers-contributeurs)
6. [Recrutement early adopters](#6-recrutement-early-adopters)
7. [Strategie de contenu](#7-strategie-de-contenu)
8. [Financement et sponsoring](#8-financement-et-sponsoring)
9. [Gouvernance et contributor ladder](#9-gouvernance-et-contributor-ladder)
10. [Metriques de sante communautaire](#10-metriques-de-sante-communautaire)
11. [Calendrier de contenu](#11-calendrier-de-contenu)
12. [Checklist de lancement](#12-checklist-de-lancement)

---

## 1. ANALYSE DES PRECEDENTS MC

### 1.1 Minestom : de 0 a 2500+ stars

**Facteurs de croissance identifies :**

| Facteur | Impact | Transposable a Warp |
|---------|--------|---------------------|
| Proposition radicale ("MC server from scratch, zero Mojang code") | Attraction initiale massive par curiosite | OUI — "proxy from scratch, blind forwarding" |
| README exemplaire avec pros/cons honnetes | Confiance immediate | OUI |
| Demo jouable (`play.minestom.net`) | Proof-of-concept tangible | OUI — demo proxy avec metriques temps reel |
| Discord cree tres tot (Issue #2, mai 2020) | Canal de feedback direct | OUI |
| Ecosysteme de libs communautaires (awesome-minestom) | Effet reseau | PLUS TARD |
| Post Hacker News ("Show HN") | Pic massif de visibilite | OUI — a preparer |
| Pas de marketing agressif, croissance organique technique | Credibilite dans le milieu MC | OUI |

**Lecons cles de Minestom :**
- Le Discord (3855 membres) est devenu le coeur de la communaute, pas GitHub Discussions
- Le projet Arena (serveur demo) a servi a la fois de vitrine et de tutoriel d'onboarding
- La reception Hacker News a montre que meme les non-joueurs MC s'interessent a l'ingenierie derriere
- Certains ont critique le choix de Java (voulaient Rust/Go) — Warp doit assumer Java 21+ comme avantage, pas comme compromis

### 1.2 Velocity : strategie de remplacement de BungeeCord

**Comment Velocity a conquis le marche :**

| Strategie | Detail |
|-----------|--------|
| **Performance mesurable** | 8x plus rapide que BungeeCord, benchmarks publics |
| **Securite comme argument #1** | modern forwarding avec secret HMAC vs IP forwarding en clair |
| **Deprecation de Waterfall** | PaperMC a arrete Waterfall en faveur de Velocity — signal fort |
| **Ecosystem PaperMC** | Integration dans l'ecosysteme Paper/Hangar (311+ plugins Velocity sur Hangar) |
| **Documentation structuree** | Page "Why Velocity?" et "Comparing with other proxies" dans la doc officielle |
| **Migration facile** | Guide `Switch-to-Velocity` communautaire, compatibilite BungeeCord plugin forwarding |

**Ce que Velocity n'a PAS fait (et que Warp peut faire) :**
- Benchmarks reproductibles publics avec methodologie documentee
- Programme d'early adopters structure
- Contenu video/conference technique
- Drain/graceful shutdown natif
- Forwarding mode par serveur
- Protection anti-bot native

### 1.3 PaperMC : la communaute de reference

**Chiffres :**
- 55 118 membres Discord
- Des centaines de milliers de serveurs en production quotidiennement
- Open Collective pour la transparence financiere
- Hangar : 2 542 plugins

**Patterns a reprendre :**
- Canaux de support dedies par produit (#paper-help, #velocity-help)
- `paper-exploit-report` pour les bugs de securite — canal prive
- Forums PaperMC en complement de Discord
- Transparence financiere via Open Collective
- Guide communautaire clair avec code de conduite

### 1.4 r/admincraft : la communaute cible

- 140 000 membres, extremement actif
- Create en 2012, plus d'une decennie d'existence
- Public : administrateurs et developpeurs de serveurs serieux
- Culture : technique, pas tolerant au spam marketing
- **Strategie d'approche** : participation authentique, resoudre des problemes avant de parler de Warp

---

## 2. POSITIONNEMENT VS VELOCITY

### 2.1 Philosophie : "ET" pas "OU"

> Warp n'existe pas pour "tuer" Velocity. Warp existe parce que certains problemes meritent une approche architecturale differente.

**Regles absolues :**
- JAMAIS de denigrement de Velocity ou de ses mainteneurs
- TOUJOURS reconnaitre les contributions de Velocity a l'ecosysteme
- Presenter Warp comme un choix supplementaire, pas un remplacement impose
- Crediter les idees inspirees par Velocity (modern forwarding, etc.)

### 2.2 Matrice de positionnement

| Axe | Velocity | Warp | Ton |
|-----|----------|------|-----|
| **Performance** | Tres bon, zlib + libdeflate natif | Blind forwarding (bypass total compression/decompression) | "Approche architecturale differente" |
| **Drain/Shutdown** | Absent (issue #431 depuis 2021) | Natif multi-phase avec Transfer Packet | "Necessite identifie par la communaute" |
| **Event system** | Race conditions documentees (#1013, #289) | Garanties strictes d'ordonnancement | "Design from scratch permet des garanties" |
| **Forwarding** | Mode unique global (#566) | Par serveur, multi-mode | "Flexibilite pour les setups heterogenes" |
| **Anti-bot** | Plugins tiers | Natif, rate-limiting integre | "Securite par defaut" |
| **Server switch** | Problemes documentes (#1251, 53 commentaires) | Machine a etats rigoureuse | "Architecture state-machine formelle" |
| **Maturite** | 5+ ans en production | Nouveau, a prouver | HONNETETE totale ici |
| **Ecosysteme plugins** | 311+ sur Hangar | A construire | "Jeune ecosysteme, contributions bienvenues" |

### 2.3 Page de comparaison (a creer dans la doc Warp)

**Structure inspiree de Velocity ("Why Velocity?" / "Comparing with other proxies") :**

1. **"Pourquoi un nouveau proxy ?"** — contexte, problemes ouverts non resolus
2. **"Differences architecturales"** — tableau factuel, liens vers les issues Velocity/BungeeCord
3. **"Ce que Warp ne fait pas encore"** — honnetete sur les limites actuelles
4. **"Quand utiliser Velocity plutot que Warp"** — reseaux qui ont besoin d'un ecosysteme plugin mature
5. **"Migration depuis Velocity"** — guide futur

**Ton imperatif :** Factuel. Jamais de superlatifs ("le meilleur", "superieur"). Toujours des liens vers les sources (issues GitHub, benchmarks).

### 2.4 Communication sur les canaux communautaires

**Template de presentation r/admincraft / SpigotMC :**
```
Titre : "[Open Source] Warp — proxy MC experimental avec blind forwarding et drain natif"

Corps :
- Courte intro : qui je suis, pourquoi ce projet
- Ce que Warp fait differemment (2-3 points max, techniques)
- Ce que Warp ne fait PAS encore (honnetete)
- Lien vers benchmarks / demo
- "Cherche des early adopters pour du feedback"
- Pas de "c'est mieux que Velocity"
```

---

## 3. PLAN DE CROISSANCE PAR PHASES

### Phase 0 : Pre-lancement (Semaines -8 a -1)

**Objectif : Preparer le terrain pour un lancement credible**

| Action | Detail | Priorite |
|--------|--------|----------|
| README exemplaire | GIF/screenshot, badges, quickstart en 5 lignes, architecture diagram | P0 |
| Documentation Getting Started | Docusaurus, 3 pages minimum : installation, configuration, premier plugin | P0 |
| Benchmarks reproductibles | JMH, comparaison avec Velocity sur throughput et latence, methodologie documentee | P0 |
| Demo fonctionnelle | Proxy Warp deployable en 1 commande (Docker), metriques Prometheus | P0 |
| Discord structure | Voir section 4 | P0 |
| 10+ Good First Issues preparees | Voir section 5 | P0 |
| Teaser sur reseaux sociaux | 2-3 tweets techniques montrant le blind forwarding, le drain system | P1 |
| Contacter 5-10 admin MC de confiance | Test prive, feedback avant lancement public | P1 |
| Blog post "Pourquoi j'ai cree Warp" | Recit authentique, problemes rencontres, vision | P1 |

### Phase 1 : Lancement — 0 a 100 stars (Semaines 1-4)

**Objectif : Validation initiale et premieres etoiles**

**Semaine 1 : Lancement coordonne**

| Jour | Action | Cible |
|------|--------|-------|
| Lundi | Publication du repo + README final | GitHub |
| Lundi | Post "Show HN" sur Hacker News | HN (10 000-30 000 visiteurs potentiels) |
| Mardi | Post r/admincraft | Reddit (140k admins MC) |
| Mardi | Post r/java, r/programming | Reddit tech |
| Mercredi | Post SpigotMC Forums | SpigotMC |
| Mercredi | Publication blog "Pourquoi Warp" | Blog technique |
| Jeudi | Post sur Dev.to (#showdev) | Dev.to |
| Vendredi | Repost sur Twitter/X avec benchmarks | Social media |

**Semaines 2-4 : Suivi**
- Repondre a CHAQUE issue/PR dans les 24h (facteur #1 de retention contributeurs)
- Engager dans les discussions HN/Reddit (reponses techniques, pas defensives)
- Publier un deuxieme article ("Architecture de Warp : comment fonctionne le blind forwarding")
- Contacter les personnes qui ont star le repo pour les inviter sur Discord
- Objectif : 50-100 stars, 20+ membres Discord

**Metriques Phase 1 :**
- Stars GitHub
- Membres Discord
- Nombre de questions/issues recues
- Temps de reponse aux issues

### Phase 2 : Traction — 100 a 1000 stars (Mois 2-6)

**Objectif : Communaute active, premiers contributeurs externes**

| Strategie | Actions concretes | Frequence |
|-----------|-------------------|-----------|
| **Contenu regulier** | 2 articles techniques/mois (blog, Dev.to) | Bi-mensuel |
| **Community calls** | Appel Discord/Zoom mensuel (30 min), demo + Q&A | Mensuel |
| **Good First Issues** | Maintenir 10+ issues ouvertes et bien documentees | Continu |
| **Awesome lists** | Soumettre Warp aux listes awesome-java, awesome-minecraft | One-shot |
| **Plugin ecosystem** | Creer 3-5 plugins d'exemple (auth, motd dynamique, load balancer) | Progressif |
| **Benchmarks mis a jour** | Republier les benchmarks a chaque release majeure | Par release |
| **Hacktoberfest** | Preparer 20+ issues labellees, promouvoir sur Discord | Annuel (octobre) |
| **Conference talk** | Soumettre un CFP a une conf Java (Devoxx, JFokus) ou MC (MINECON community) | 1-2x/an |
| **Cross-promotion** | Articles "listicles" incluant d'autres projets MC open source | Mensuel |

**Tactiques de croissance organique :**
- Analyser les stargazers (quels autres repos ils suivent) pour identifier des communautes cibles
- Soumettre a daily.dev, Console.dev, GitHub20K pour la decouverte
- Creer un canal `#showcase` sur Discord pour que les utilisateurs partagent leurs deployments
- Celebrer chaque milestone (100, 250, 500, 750, 1000 stars) avec un post communautaire
- Commencer les celebratory posts a des chiffres originaux (ex: "735 stars!") pour se demarquer

**Metriques Phase 2 :**
- Stars/semaine (objectif : 15-25)
- Contributeurs externes uniques
- PRs merges de contributeurs externes
- Nombre de plugins communautaires
- Membres Discord actifs mensuellement

### Phase 3 : Acceleration — 1000 a 5000 stars (Mois 7-18)

**Objectif : Projet de reference, adoption production, ecosysteme**

| Strategie | Actions concretes |
|-----------|-------------------|
| **Production readiness** | Guide de deployment K8s, Helm chart, exemples Terraform |
| **Case studies** | Documenter 2-3 deployments en production (avec accord des reseaux) |
| **Video content** | Serie YouTube "Building Warp" (architecture, decisions, benchmarks) |
| **Conference circuit** | Talks a Devoxx, QCon, KubeCon (angle: proxy MC cloud-native) |
| **Plugin marketplace** | Registry de plugins avec documentation, exemples, templates |
| **Internship/mentorship** | Programme de mentorat formel pour nouveaux contributeurs |
| **Governance formelle** | RFC process, contributor ladder, decision-making transparent |
| **Roadmap publique** | GitHub Projects mis a jour mensuellement |
| **Partenariats hosting** | Approcher les hebergeurs MC pour support natif Warp |
| **Traduction README** | FR, ES, PT, ZH, JA — acceder aux trending lists par langue |
| **GitHub Sponsors** | Ouvrir le financement communautaire |

**Metriques Phase 3 :**
- Nombre de reseaux en production utilisant Warp
- Nombre de plugins dans le registry
- Nombre de contributeurs avec 3+ PRs merges
- Downloads mensuels (Maven Central)
- Revenue GitHub Sponsors / Open Collective

---

## 4. STRUCTURE DISCORD

### 4.1 Architecture des canaux

```
WARP PROXY
├── INFORMATION
│   ├── #welcome              — Message d'accueil, liens utiles, regles
│   ├── #rules                — Code de conduite detaille
│   ├── #announcements        — Releases, breaking changes, events (lecture seule)
│   ├── #changelog            — Webhook GitHub : chaque release auto-postee
│   └── #roles                — Attribution de roles via reactions/onboarding
│
├── COMMUNITY
│   ├── #general              — Discussions libres sur Warp
│   ├── #introductions        — Presentez-vous (forum channel)
│   ├── #showcase             — Projets/serveurs utilisant Warp
│   ├── #off-topic            — Hors-sujet, MC en general
│   └── #memes               — Contenu leger (optionnel, a activer selon la taille)
│
├── SUPPORT
│   ├── #help                 — Questions utilisateurs (forum channel)
│   ├── #plugin-help          — Questions sur le dev de plugins (forum channel)
│   ├── #migration            — Aide migration depuis Velocity/BungeeCord
│   └── #faq                  — Questions frequentes epinglees
│
├── DEVELOPMENT
│   ├── #dev-general          — Discussions entre contributeurs
│   ├── #architecture         — Decisions de design, RFCs
│   ├── #code-review          — Demandes de review, discussions PRs
│   ├── #ci-notifications     — Webhook : builds, tests, releases (lecture seule)
│   ├── #github-feed          — Webhook : issues, PRs, commits (lecture seule)
│   └── #benchmarks           — Resultats de benchmarks, comparaisons
│
├── PLUGINS
│   ├── #plugin-dev           — Developpement de plugins Warp
│   ├── #plugin-showcase      — Partage de plugins communautaires
│   └── #plugin-ideas         — Idees et demandes de plugins
│
├── VOICE
│   ├── General Voice         — Discussions vocales libres
│   └── Community Call        — Appels mensuels planifies
│
├── MODERATION (prive)
│   ├── #mod-log              — Actions de moderation
│   ├── #mod-discussion       — Discussion entre mods
│   └── #security-reports     — Reports de vulnerabilites (prive, acces restreint)
│
└── TEAM (prive)
    ├── #team-internal        — Discussions internes maintainers
    ├── #roadmap-planning     — Planification roadmap
    └── #partner-relations    — Relations hebergeurs/sponsors
```

### 4.2 Roles

| Role | Couleur | Permissions | Attribution |
|------|---------|-------------|-------------|
| `@Maintainer` | Rouge | Admin complet | Manuelle |
| `@Core Contributor` | Orange | Gestion messages, pins, threads | Manuelle (5+ PRs merges) |
| `@Contributor` | Vert | Acces #dev-general, reactions | Automatique (1+ PR merge) |
| `@Plugin Developer` | Bleu | Acces canaux plugins | Self-assign via onboarding |
| `@Server Admin` | Violet | Acces canaux support avances | Self-assign via onboarding |
| `@Community` | Gris | Permissions de base | Par defaut |
| `@Beta Tester` | Jaune | Acces canaux beta prives | Par invitation |
| `@Moderator` | Cyan | Mute, kick, gestion messages | Manuelle |

### 4.3 Bots et automatisation

| Bot | Fonction | Config |
|-----|----------|--------|
| **Discord AutoMod** | Filtrage spam, liens, mots interdits | Natif Discord, gratuit |
| **GitHub Bot (webhook)** | Notifications issues, PRs, releases dans #github-feed et #ci-notifications | Webhook GitHub natif |
| **Carl-bot** | Reaction roles, logs de moderation, automod avance | Free tier suffisant au debut |
| **Ticket Bot** | Systeme de tickets pour bug reports prives | TicketTool ou Ticket Bot |
| **MEE6 ou Dyno** | Welcome messages, commandes custom, niveaux | Un seul, pas les deux |

### 4.4 Onboarding flow

1. Nouveau membre rejoint -> voit uniquement `#welcome` et `#rules`
2. Repond aux questions d'onboarding Discord natif :
   - "Quel est votre role ?" (Admin serveur / Developpeur plugin / Contributeur / Curieux)
   - "Utilisez-vous deja un proxy MC ?" (Velocity / BungeeCord / Aucun / Autre)
3. Roles attribues automatiquement -> canaux pertinents debloquies
4. Message de bienvenue automatique dans `#introductions`
5. Bot envoie un DM avec les 3 liens les plus utiles (Getting Started, GitHub, FAQ)

### 4.5 Regles de moderation

**Principes :**
- Tolerance zero pour les attaques personnelles, le racisme, le sexisme
- Pas de denigrement de Velocity ou d'autres projets
- Pas de piratage ou reverse engineering de code Mojang
- Discussions techniques encouragees, flame wars interdites
- Premier avertissement verbal, deuxieme mute 24h, troisieme ban

**Recrutement moderateurs :**
- Identifier 3-5 membres actifs et bienveillants dans les 2 premiers mois
- Documenter les procedures de moderation dans un canal prive
- Rotation des moderateurs pour eviter le burnout
- Check-in mensuel sur la charge de moderation

---

## 5. STRATEGIE PREMIERS CONTRIBUTEURS

### 5.1 Good First Issues : la fondation

**Statistique cle :** Taguer ~25% des issues en "Good First Issue" augmente de 13% le nombre de nouveaux contributeurs.

**Template pour chaque Good First Issue :**
```markdown
## Description
[Description claire du probleme en 2-3 phrases]

## Contexte
[Pourquoi c'est important, lien vers la feature/bug parente]

## Fichier(s) concerne(s)
- `warp-proxy/src/main/java/...`
- `warp-api/src/main/java/...`

## Approche suggeree
1. [Etape 1]
2. [Etape 2]
3. [Etape 3]

## Tests attendus
- [ ] Test unitaire dans `...Test.java`
- [ ] Le build passe (`./gradlew build`)

## Ressources
- [Lien vers la doc pertinente]
- [Lien vers le code existant similaire]

## Difficulte estimee
🟢 Debutant (~1-2h) / 🟡 Intermediaire (~3-5h) / 🔴 Avance (~1 jour+)
```

**10 premieres Good First Issues a preparer avant le lancement :**

| # | Issue | Difficulte | Module |
|---|-------|------------|--------|
| 1 | Ajouter des tests unitaires pour le NetworkBuffer | Debutant | warp-protocol |
| 2 | Implementer un plugin d'exemple "MOTD dynamique" | Debutant | warp-plugin-examples |
| 3 | Ajouter la Javadoc manquante sur l'API publique | Debutant | warp-api |
| 4 | Creer un Dockerfile optimise multi-stage | Intermediaire | warp-app |
| 5 | Implementer le rate-limiting par IP configurable | Intermediaire | warp-proxy |
| 6 | Ajouter le support des metrics Prometheus | Intermediaire | warp-proxy |
| 7 | Ecrire un guide "Premier plugin Warp" | Debutant | docs |
| 8 | Implementer le hot-reload de la configuration | Intermediaire | warp-proxy |
| 9 | Ajouter les ADRs manquants (decisions d'architecture) | Debutant | docs |
| 10 | Implementer le support des annotations @API Guardian | Intermediaire | warp-api |

### 5.2 Onboarding des contributeurs

**CONTRIBUTING.md structure :**

1. **Quick Start** — Fork, clone, build en 3 commandes
2. **Architecture rapide** — Schema des modules, ou modifier quoi
3. **Conventions** — Conventional Commits, format Palantir, Javadoc obligatoire sur l'API
4. **Workflow PR** — Branch naming, review process, merge criteria
5. **Tests** — Comment ecrire et lancer les tests
6. **Ou demander de l'aide** — Discord #dev-general, GitHub Discussions

**Processus de review PR :**
- Acknowledge dans les 24h (meme si review plus tard)
- Review substantielle dans les 72h
- CI doit passer (build + tests + format + static analysis)
- Au moins 1 maintainer approve
- Feedback constructif et specifique, jamais "c'est nul"
- Pour les premiers contributeurs : etre particulierement accueillant et pedagogique

### 5.3 Hacktoberfest (annuel, octobre)

**Preparation (septembre) :**
- Creer 20+ issues labellees `hacktoberfest`
- Mixer les difficultes : 50% debutant, 30% intermediaire, 20% avance
- Inclure des issues non-code : documentation, traductions, exemples
- Post d'annonce sur Discord, Twitter, r/admincraft

**Pendant (octobre) :**
- Review rapide des PRs (priorite Hacktoberfest)
- Post hebdomadaire "Hacktoberfest progress" sur Discord
- Spotlight des meilleurs contributeurs

**Apres (novembre) :**
- Post recapitulatif avec stats
- Inviter les meilleurs contributeurs a devenir "reguliers"
- Proposer le role `@Contributor` sur Discord

### 5.4 Programme de mentorat (Phase 3)

**Format :**
- Pairing 1:1 entre un contributeur confirme et un nouveau
- Duree : 4 semaines, 1 session de 30 min/semaine
- Objectif : le nouveau contributeur merge sa premiere PR significative
- Outils : Discord voice, screen sharing, GitHub code review

**Criteres du mentor :**
- 5+ PRs merges
- Attitude bienveillante demontree
- Volontaire (pas impose)

---

## 6. RECRUTEMENT EARLY ADOPTERS

### 6.1 Programme Beta Tester

**Structure :**

| Aspect | Detail |
|--------|--------|
| **Taille** | 10-20 reseaux MC pour le beta, 3-5 pour l'alpha |
| **Selection** | Taille variee (petit serveur a reseau 500+ joueurs) |
| **Engagement** | Tester en staging/pre-prod pendant 2-4 semaines |
| **Feedback** | Formulaire structure + canal Discord prive #beta-feedback |
| **Avantages** | Mention dans le README, influence sur la roadmap, support prioritaire |
| **NDA** | Aucun — tout est open source, mais discretion encouragee avant l'annonce publique |

**Formulaire de candidature beta :**
```
1. Nom du reseau / projet
2. Taille (joueurs simultanes, nombre de serveurs)
3. Proxy actuel (Velocity / BungeeCord / autre)
4. Cas d'usage specifique (minijeux, survie, reseau multi-serveurs...)
5. Setup technique (bare metal / cloud / K8s)
6. Disponibilite pour le feedback (heures/semaine)
7. Interet principal (performance / drain / securite / plugins / autre)
```

### 6.2 Canaux de recrutement

| Canal | Approche | Timing |
|-------|----------|--------|
| **r/admincraft** | Post "cherche testeurs beta pour nouveau proxy MC" | Pre-lancement |
| **SpigotMC Forums** | Thread dans "Proxy Discussion" | Pre-lancement |
| **PaperMC Discord** | PAS de spam — discussion organique uniquement | Post-lancement |
| **Twitter/X** | Thread technique avec screenshots/metriques | Pre-lancement |
| **Contacts directs** | DM a des admins MC connus dans la communaute | Pre-lancement |
| **MC-Market / BuiltByBit** | Thread dans "Server Administration" | Post-lancement |
| **Discord Admincraft** | Discussion technique authentique | Post-lancement |

### 6.3 Boucle de feedback

**Cycle de 2 semaines :**

```
Semaine 1:
  Lundi    — Release beta avec changelog
  Mar-Ven  — Beta testers deploient et utilisent
  Vendredi — Formulaire de feedback envoye

Semaine 2:
  Lundi    — Aggregation du feedback
  Mardi    — Triage : bugs critiques, ameliorations, feature requests
  Mer-Jeu  — Corrections prioritaires
  Vendredi — Recapitulatif poste dans #beta-feedback + prochaine iteration
```

**Formulaire de feedback bi-mensuel :**
1. Score NPS (0-10) : "Recommanderiez-vous Warp ?"
2. Qu'avez-vous aime cette semaine ?
3. Qu'est-ce qui vous a frustre ?
4. Bugs rencontres ? (avec logs si possible)
5. Feature la plus demandee pour la prochaine iteration ?

### 6.4 De beta tester a champion communautaire

**Parcours type :**
1. **Beta tester** → Teste, donne du feedback
2. **Early adopter** → Deploie en production, partage son experience
3. **Champion** → Ecrit un temoignage, fait une demo a sa communaute
4. **Ambassadeur** → Repond aux questions sur r/admincraft, recommande Warp

**Incentives (non-monetaires) :**
- Badge `@Beta Tester` / `@Early Adopter` sur Discord
- Mention dans la page "Adopters" du site
- Influence directe sur la roadmap (vote sur les priorites)
- Acces anticipe aux releases
- Invitation aux community calls

---

## 7. STRATEGIE DE CONTENU

### 7.1 Types de contenu

**4 categories (inspirees du playbook Star History) :**

| Type | Objectif | Exemple | Frequence |
|------|----------|---------|-----------|
| **Direct** | Presenter Warp | "Warp : un nouveau proxy MC avec blind forwarding" | Au lancement + chaque release majeure |
| **Indirect** | Resoudre un probleme plus large | "Comment deployer un reseau MC sur Kubernetes en 2026" | Mensuel |
| **Listicle** | Figurer dans des comparaisons | "5 proxies MC open source compares" | Trimestriel |
| **Building in public** | Partager le voyage | "Comment j'ai atteint 500 stars en 3 mois" | Aux milestones |

### 7.2 Contenu technique phare

**Articles "piliers" (a ecrire avant ou juste apres le lancement) :**

| # | Titre | Plateforme | Objectif |
|---|-------|------------|----------|
| 1 | "Pourquoi j'ai cree Warp : les problemes non resolus des proxies MC" | Blog + Dev.to | Recit fondateur, liens vers les issues Velocity/BungeeCord |
| 2 | "Blind forwarding : comment bypasser la decompression pour 4-5x de throughput" | Blog + Dev.to + HN | Article technique deep-dive, benchmarks |
| 3 | "Drain natif pour Minecraft : zero downtime deployments avec Warp" | Blog + Dev.to | Probleme reel, solution concrete |
| 4 | "Machine a etats pour le server switching MC : pourquoi les proxies crashent" | Blog | Architecture technique, diagrammes |
| 5 | "Benchmarks Warp vs Velocity : methodologie et resultats" | Blog + GitHub | Transparence, reproductibilite |

**Methodologie de benchmark :**
- Environnement : specifications exactes (CPU, RAM, OS, JDK)
- Outil : JMH pour micro-benchmarks, Yardstick-inspire pour end-to-end
- Metriques : throughput (paquets/s), latence p50/p95/p99, CPU%, memoire
- Code source des benchmarks dans le repo (dossier `benchmarks/`)
- Resultats mis a jour a chaque release
- Comparaison honnete : inclure les scenarios ou Velocity est meilleur

### 7.3 Contenu video

| Type | Format | Frequence | Plateforme |
|------|--------|-----------|------------|
| **Architecture walkthrough** | 15-20 min, screencast avec diagrammes | Trimestriel | YouTube |
| **Release demo** | 5-10 min, nouvelles features en action | Par release majeure | YouTube |
| **Community call recording** | 30-60 min, Q&A + demo | Mensuel | YouTube (unlisted) + Discord |
| **Conference talks** | 30-45 min, preparation formelle | 1-2x/an | YouTube (conf officielle) |
| **Short-form** | 60-90 sec, feature highlight | Mensuel | Twitter/X, YouTube Shorts |

### 7.4 Conferences et talks

**Cibles prioritaires :**

| Conference | Angle | CFP |
|-----------|-------|-----|
| **Devoxx (Belgique/France)** | "Building a High-Performance Minecraft Proxy with Java 21 Virtual Threads" | Mars-Avril |
| **JFokus (Suede)** | "Blind Forwarding: Zero-Copy Packet Proxying in Java" | Automne |
| **QCon** | "State Machines in Production: Lessons from a Minecraft Proxy" | Variable |
| **Minecraft community events** | "Warp: A New Approach to MC Proxying" | Variable |
| **Meetups Java locaux** | Demo live, format court 20 min | Continu |

### 7.5 Presence sur les reseaux sociaux

**Twitter/X :**
- Compte `@WarpProxy` (ou similaire)
- 3-5 tweets techniques/semaine
- Partager des snippets de code, benchmarks, diagrams
- Retweet des utilisateurs qui mentionnent Warp
- Thread techniques approfondis pour les releases

**Reddit :**
- Participation organique sur r/admincraft, r/java, r/programming
- Pas de spam, pas de cross-post massif
- Repondre aux questions sur les proxies MC meme quand Warp n'est pas mentionne
- Post majeur uniquement pour les releases et les articles techniques

---

## 8. FINANCEMENT ET SPONSORING

### 8.1 GitHub Sponsors

**Quand ouvrir :** Phase 2 (apres 100+ stars, communaute etablie)

**Structure des tiers :**

| Tier | Prix/mois | Avantage |
|------|-----------|----------|
| Supporter | 5 USD | Badge sponsor sur Discord, nom dans SPONSORS.md |
| Backer | 15 USD | + Vote sur les priorites de roadmap |
| Bronze | 50 USD | + Logo petit sur le README |
| Silver | 100 USD | + Logo moyen sur le README + site |
| Gold | 250 USD | + Mention dans les release notes |
| Platinum | 500 USD | + Session 1:1 mensuelle avec un maintainer |

### 8.2 Open Collective

**Avantages par rapport a GitHub Sponsors seul :**
- Transparence totale des depenses (facteur de confiance)
- Pas de limite de 30 jours pour utiliser les fonds
- Fiscal host (Open Source Collective) gere la comptabilite
- Frais : 10% (GitHub ne prend pas de frais supplementaire)

**Utilisation des fonds :**
- Infrastructure (serveurs CI, hebergement demo, domaine)
- Bounties pour les contributions prioritaires
- Conference travel pour les talks
- Materiel de test (serveurs bare metal pour benchmarks)

### 8.3 Partenariats hebergeurs MC

**Approche :**

| Hebergeur | Taille | Programme existant | Approche Warp |
|-----------|--------|---------------------|---------------|
| **Sparked Host** | Moyen | Partnership program (25k+ downloads) | Post-1000 stars |
| **Apex Hosting** | Grand | Sponsorship program | Post-1000 stars |
| **Nodecraft** | Grand | 100k+ downloads requis | Post-2000 stars |
| **Bloom.host** | Moyen-grand | Communautaire MC | Partenariat technique (docs) |
| **PebbleHost** | Moyen | Populaire r/admincraft | Partenariat test |

**Ce qu'on peut offrir aux hebergeurs :**
- Logo sur le README et le site (visibilite devs MC)
- Support Warp natif dans leur panel (configuration simplifiee)
- Co-creation de guides de deployment
- Benchmarks sur leur infrastructure (marketing mutuel)

**Ce qu'on demande :**
- Serveurs pour la CI et les benchmarks
- Promotion de Warp aupres de leurs clients
- Feedback sur les besoins des hebergeurs

### 8.4 Bounties

**Programme de bounties (Phase 2+) :**
- Financer des contributions specifiques via Open Collective
- Montants : 50-500 USD selon la complexite
- Issues labellees `bounty:$50`, `bounty:$100`, etc.
- Paiement apres merge et review
- Pas de bounty sur les Good First Issues (garder l'aspect "apprentissage")

---

## 9. GOUVERNANCE ET CONTRIBUTOR LADDER

### 9.1 Contributor Ladder (inspire CNCF)

```
PARCOURS CONTRIBUTEUR WARP
==========================

Community Member (tout le monde)
    │
    ├─ Utilise Warp, participe sur Discord
    │
    v
Contributor (1+ PR merge)
    │
    ├─ Role @Contributor sur Discord
    ├─ Nom dans CONTRIBUTORS.md
    │
    v
Regular Contributor (5+ PRs merges sur 3+ mois)
    │
    ├─ Role @Core Contributor sur Discord
    ├─ Invitation aux community calls internes
    ├─ Peut etre assigne comme reviewer
    │
    v
Maintainer (vote par les maintainers existants)
    │
    ├─ Write access au repo
    ├─ Vote sur les decisions (lazy consensus)
    ├─ Responsabilite de review et merge
    ├─ Role @Maintainer sur Discord
    │
    v
Lead Maintainer
    │
    ├─ Vision globale du projet
    ├─ Decision finale en cas de blocage
    ├─ Representation publique du projet
```

### 9.2 Decision-making

| Type de decision | Processus |
|-----------------|-----------|
| Bug fix, petite amelioration | 1 maintainer approve → merge |
| Nouvelle feature | 2 maintainers approve + CI pass → merge |
| Breaking change API | RFC process (2 semaines discussion minimum) |
| Nouveau maintainer | Proposition par un maintainer + majorite simple |
| Retrait d'un maintainer | Supermajority (2/3) — inactivite 1 an+, violation CoC |
| Changement de licence | Unanimite |

### 9.3 Code de conduite

**Base :** Contributor Covenant v2.1 (standard de l'industrie)

**Ajouts specifiques a Warp :**
- Pas de denigrement d'autres projets (Velocity, BungeeCord, Gate)
- Pas de discussion sur le piratage ou l'exploitation de vulnerabilites MC
- Respect des equipes de Mojang et de leur travail
- Langue principale : anglais pour le code et les PRs, franglais tolere sur Discord

### 9.4 Processus RFC

**Pour les changements impactant l'API publique ou l'architecture :**

1. **Proposition** : Issue avec template RFC (resume, motivation, design, alternatives)
2. **Discussion** : 2 semaines minimum, feedback communautaire
3. **Revision** : L'auteur integre le feedback
4. **Decision** : Vote des maintainers
5. **Implementation** : PR(s) referencant le RFC
6. **Retrospective** : Apres implementation, documenter les lecons apprises

---

## 10. METRIQUES DE SANTE COMMUNAUTAIRE

### 10.1 Framework CHAOSS adapte a Warp

**CHAOSS** (Community Health Analytics in Open Source Software) definit des metriques implementation-agnostiques. Voici celles adaptees a Warp :

| Categorie | Metrique | Cible Phase 1 | Cible Phase 2 | Cible Phase 3 |
|-----------|----------|---------------|---------------|---------------|
| **Activite** | Stars GitHub | 100 | 1000 | 5000 |
| **Activite** | Forks | 10 | 100 | 500 |
| **Activite** | Issues ouvertes/fermees par mois | 10/5 | 50/30 | 100/70 |
| **Activite** | PRs merges/mois | 5 | 20 | 50 |
| **Contributeurs** | Contributeurs uniques/mois | 3 | 15 | 40 |
| **Contributeurs** | Nouveaux contributeurs/mois | 2 | 8 | 15 |
| **Contributeurs** | Retention a 3 mois (% contributeurs revenant) | 30% | 40% | 50% |
| **Reactivite** | Temps median premiere reponse issue | <24h | <12h | <8h |
| **Reactivite** | Temps median merge PR | <7 jours | <5 jours | <3 jours |
| **Communaute** | Membres Discord | 50 | 500 | 2000 |
| **Communaute** | Messages Discord/semaine | 50 | 300 | 1000 |
| **Communaute** | Participants community call | 5 | 20 | 50 |
| **Adoption** | Downloads Maven Central/mois | 50 | 500 | 5000 |
| **Adoption** | Reseaux en production declares | 0 | 5 | 20 |
| **Ecosysteme** | Plugins communautaires | 0 | 10 | 50 |
| **Diversite** | Ratio code/non-code contributions | 80/20 | 70/30 | 60/40 |

### 10.2 Outils de mesure

| Outil | Mesure | Gratuit |
|-------|--------|---------|
| **GitHub Insights** | Stars, forks, traffic, contributeurs | Oui |
| **Star History** (star-history.com) | Courbe de croissance des stars | Oui |
| **Cauldron.io** (CHAOSS) | Metriques communautaires completes | Freemium |
| **Discord Server Insights** | Membres actifs, retention, canaux populaires | Oui (built-in) |
| **Google Analytics** | Traffic site/documentation | Oui |
| **Maven Central stats** | Downloads | Oui |
| **GitHub Actions** | Temps de CI, tests pass rate | Oui |

### 10.3 Revue mensuelle de sante

**Checklist mensuelle (30 min) :**
- [ ] Comparer les metriques cles au mois precedent
- [ ] Identifier les 3 metriques en amelioration et les 3 en declin
- [ ] Lire les 5 dernieres issues/PRs de nouveaux contributeurs — sont-elles bien accueillies ?
- [ ] Verifier le temps de reponse median — respecte-t-on les SLAs ?
- [ ] Scanner Discord pour des signaux de friction ou de toxicite
- [ ] Publier un resume transparent sur Discord #announcements

### 10.4 Signaux d'alerte

| Signal | Seuil | Action |
|--------|-------|--------|
| Temps de reponse issue > 48h | 3 issues consecutives | Recruter un reviewer, reduire la charge |
| Retention contributeur < 20% | 2 mois consecutifs | Audit onboarding, interviews des contributeurs perdus |
| Stars/semaine en chute | -50% sur 4 semaines | Nouveau contenu, post communautaire, feature release |
| Messages Discord/semaine < 50 | 2 semaines consecutives | Lancer une discussion, poser des questions, community call |
| 0 nouveaux contributeurs/mois | 1 mois | Nouvelles Good First Issues, post Reddit, Hacktoberfest |

---

## 11. CALENDRIER DE CONTENU

### 11.1 Calendrier type — 6 premiers mois

**Mois 1 (Lancement)**

| Semaine | Contenu | Plateforme | Type |
|---------|---------|------------|------|
| S1 | "Pourquoi j'ai cree Warp" | Blog, Dev.to, HN | Direct |
| S1 | Post lancement | r/admincraft, r/java, SpigotMC | Direct |
| S2 | "Blind forwarding explique" | Blog, Dev.to | Technique |
| S3 | Premier community call | Discord | Engagement |
| S4 | Recap du premier mois (stats, feedback) | Blog, Discord | Building in public |

**Mois 2**

| Semaine | Contenu | Plateforme | Type |
|---------|---------|------------|------|
| S1 | "Drain natif : zero downtime pour MC" | Blog, Dev.to | Technique |
| S2 | Tutorial "Premier plugin Warp en 10 min" | Blog, YouTube | Indirect |
| S3 | Community call #2 | Discord | Engagement |
| S4 | "Benchmarks Warp v0.2 : resultats et methodologie" | Blog, GitHub | Technique |

**Mois 3**

| Semaine | Contenu | Plateforme | Type |
|---------|---------|------------|------|
| S1 | "Deployer un reseau MC sur Kubernetes avec Warp" | Blog, Dev.to | Indirect |
| S2 | Thread Twitter "5 choses que j'ai apprises en creant un proxy MC" | Twitter/X | Building in public |
| S3 | Community call #3 | Discord | Engagement |
| S4 | Guest post sur un blog MC/Java | Externe | Indirect |

**Mois 4**

| Semaine | Contenu | Plateforme | Type |
|---------|---------|------------|------|
| S1 | "5 proxies MC open source compares" | Blog, Dev.to | Listicle |
| S2 | Video "Architecture Warp en 15 min" | YouTube | Technique |
| S3 | Community call #4 | Discord | Engagement |
| S4 | Milestone post (ex: "500 stars, merci !") | Blog, Twitter, Discord | Building in public |

**Mois 5**

| Semaine | Contenu | Plateforme | Type |
|---------|---------|------------|------|
| S1 | "Comment contribuer a Warp : guide pas a pas" | Blog | Onboarding |
| S2 | Case study d'un early adopter | Blog | Social proof |
| S3 | Community call #5 | Discord | Engagement |
| S4 | "State machines pour le networking en Java" | Blog, Dev.to | Technique |

**Mois 6**

| Semaine | Contenu | Plateforme | Type |
|---------|---------|------------|------|
| S1 | "Bilan 6 mois : de 0 a X stars" | Blog, Dev.to, HN | Building in public |
| S2 | Video demo des nouvelles features | YouTube | Direct |
| S3 | Community call #6 | Discord | Engagement |
| S4 | Soumission CFP conference (Devoxx ou autre) | Externe | Long-terme |

### 11.2 Rythme par plateforme

| Plateforme | Frequence | Jours optimaux |
|-----------|-----------|----------------|
| Blog Warp | 2x/mois | Mardi ou mercredi matin |
| Dev.to | 2x/mois (cross-post) | Mardi ou mercredi |
| Twitter/X | 3-5x/semaine | Variable |
| Reddit | 1-2x/mois max | Mardi-jeudi |
| YouTube | 1x/mois | Mercredi |
| Discord announcements | 1-2x/semaine | Variable |
| Community call | 1x/mois | Dernier mercredi du mois, 19h CET |
| Hacker News | 2-3x/an max | Mardi-mercredi matin PT |

---

## 12. CHECKLIST DE LANCEMENT

### 12.1 Avant le premier commit public

- [ ] README exemplaire (badges, GIF, quickstart, architecture)
- [ ] LICENSE (Apache 2.0)
- [ ] CONTRIBUTING.md complet
- [ ] CODE_OF_CONDUCT.md (Contributor Covenant v2.1)
- [ ] Templates GitHub (issues: bug report, feature request, RFC; PR template)
- [ ] Labels configures (voir section 5)
- [ ] 10+ Good First Issues preparees
- [ ] CI fonctionnelle (build + test + lint + format)
- [ ] Documentation Getting Started (Docusaurus)
- [ ] Page "Why Warp?" / "Comparing with other proxies"
- [ ] Benchmarks reproductibles avec resultats
- [ ] Docker image fonctionnelle
- [ ] Roadmap publique sur GitHub Projects

### 12.2 Avant l'annonce publique

- [ ] Discord structure et teste (canaux, roles, bots, onboarding)
- [ ] Compte Twitter/X cree et actif (quelques tweets techniques pre-lancement)
- [ ] Blog post "Pourquoi j'ai cree Warp" ecrit et relu
- [ ] 5-10 beta testers recrutes et actifs
- [ ] Feedback beta integre
- [ ] README social card optimisee (og:image pour les partages)
- [ ] Site web minimal (meme une seule page)
- [ ] Publication Maven Central configuree

### 12.3 Jour du lancement

- [ ] Repo passe en public
- [ ] Blog post publie
- [ ] "Show HN" poste sur Hacker News (mardi ou mercredi matin PT)
- [ ] Post r/admincraft
- [ ] Post r/java, r/programming (espaces de 24h)
- [ ] Announcement Discord
- [ ] Tweet de lancement
- [ ] Dev.to cross-post (attendre 24h pour indexation Google du blog)
- [ ] Monitoring des canaux pour repondre aux questions (toute la journee)

### 12.4 Semaine post-lancement

- [ ] Repondre a CHAQUE commentaire HN, Reddit, issue GitHub
- [ ] Fixer les bugs remontes en priorite absolue
- [ ] Publier un second article technique (blind forwarding deep-dive)
- [ ] Inviter les personnes engagees a rejoindre Discord
- [ ] Premiere analyse des metriques
- [ ] Thank-you post aux early adopters

---

## ANNEXES

### A. Ressources cles

| Ressource | Lien | Usage |
|-----------|------|-------|
| GitHub Open Source Guides | opensource.guide | Communaute, gouvernance, legal |
| CNCF Contributor Strategy | contribute.cncf.io | Contributor ladder, roadmaps, onboarding |
| CHAOSS Metrics | chaoss.community | Metriques de sante communautaire |
| Star History | star-history.com | Tracking croissance stars |
| GitHub Blog "4 Steps" | github.blog | Fondamentaux communaute |
| Glasskube "10 Steps Discord" | glasskube.dev/blog/discord-setup | Structure Discord OSS |
| DoltHub "Running OSS Discord" | dolthub.com/blog | Retour d'experience reel |
| Velocity "Why Velocity?" | docs.papermc.io/velocity/why-velocity | Exemple de positionnement |

### B. Lecons des echecs a eviter

| Anti-pattern | Consequence | Mitigation |
|-------------|-------------|------------|
| Ignorer les issues/PRs | Contributeurs partent et ne reviennent jamais | SLA de reponse 24h |
| Comparer agressivement a Velocity | Reputation toxique, rejet communautaire | Ton factuel, reconnaissance des merites |
| Trop de canaux Discord | Fragmentation, channels morts | Commencer minimal, ajouter selon le besoin |
| Pas de documentation | Barriere d'entree insurmontable | Getting Started AVANT le code |
| Promettre plus que livrer | Perte de confiance | Roadmap realiste, honnetete sur les limites |
| Accepter tous les PRs sans review | Qualite code s'effondre | Standards clairs, CI stricte |
| Governance floue | Decisions arbitraires, frustration | GOVERNANCE.md des le debut |
| Burnout du mainteneur | Projet meurt | Deleguer, recruter, dire non |

### C. Comparaison des communautes MC de reference

| Metrique | PaperMC | Minestom | Velocity | Warp (cible 18 mois) |
|----------|---------|----------|----------|---------------------|
| Discord members | 55 118 | 3 855 | (inclus dans PaperMC) | 2 000 |
| GitHub stars | 10 000+ | 2 500+ | 2 000+ | 3 000-5 000 |
| Contributeurs | 500+ | 200+ | 100+ | 50+ |
| Plugins | 2 542 (Hangar) | 50+ (libs) | 311 (Hangar) | 30+ |
| Age | 8+ ans | 5+ ans | 5+ ans | 0 |
| Financement | Open Collective | Donations | PaperMC | GitHub Sponsors + OC |

---

## RESUME EXECUTIF

**Warp peut atteindre 3000-5000 stars et une communaute de 2000+ membres Discord en 18 mois** si les conditions suivantes sont remplies :

1. **Credibilite technique pre-lancement** — benchmarks reproductibles, demo fonctionnelle, documentation Getting Started
2. **Lancement coordonne multi-plateforme** — HN + Reddit + SpigotMC + Dev.to dans la meme semaine
3. **Reactivite absolue** — reponse < 24h a chaque issue, PR, question Discord
4. **Contenu regulier** — 2 articles techniques/mois, 1 community call/mois
5. **Positionnement respectueux** — "ET" pas "OU", factuel, honnete sur les limites
6. **Good First Issues** — flux constant d'issues accessibles et bien documentees
7. **Programme beta structure** — 10-20 reseaux MC testant avant chaque release
8. **Governance transparente** — contributor ladder clair, decisions publiques, roadmap ouverte

**La regle d'or :** Rien ne tue plus vite un projet open-source que des issues sans reponse et des PRs qui pourrissent. La reactivite est le facteur #1 de retention des contributeurs.
