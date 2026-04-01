# Architecture de documentation pour Warp Proxy

**Date** : 31 mars 2026
**Objectif** : Concevoir LA meilleure documentation de l'ecosysteme MC proxy -- superieure a Velocity, Paper, Gate, et inspiree des meilleurs de l'industrie (Caddy, Traefik, Envoy).
**Sources** : PaperMC Docs, Gate docs, SpigotMC Wiki, Minestom Wiki, Caddy docs, Traefik docs, Envoy docs, Starlight/Docusaurus/MkDocs, ADR/C4, Crowdin, Codapi, Pagefind

---

## TABLE DES MATIERES

1. [Audit de l'existant](#1-audit-de-lexistant)
2. [Principes fondateurs](#2-principes-fondateurs)
3. [Choix de l'outillage](#3-choix-de-loutillage)
4. [Arbre de pages complet](#4-arbre-de-pages-complet)
5. [Documentation interactive](#5-documentation-interactive)
6. [Architecture Decision Records](#6-architecture-decision-records)
7. [Diagrammes d'architecture (C4)](#7-diagrammes-darchitecture-c4)
8. [Internationalisation](#8-internationalisation)
9. [Video et multimedia](#9-video-et-multimedia)
10. [CI/CD pour la documentation](#10-cicd-pour-la-documentation)
11. [Priorites de contenu](#11-priorites-de-contenu)
12. [Calendrier de livraison](#12-calendrier-de-livraison)

---

## 1. AUDIT DE L'EXISTANT {#1-audit-de-lexistant}

### 1.1 Velocity (PaperMC Docs)

**URL** : https://docs.papermc.io/velocity/

**Points forts :**
- Site moderne (Starlight + Pagefind), dark mode, typographie soignee
- Getting Started clair
- Section configuration avec les options essentielles
- Section tuning / performance

**Points faibles :**
- Documentation **incomplete** : la majorite de l'API n'est pas documentee
- Pas de guide de migration depuis BungeeCord (juste "utilisez Velocity")
- Pas de guide de deploiement (Docker, K8s, bare metal)
- Pas de troubleshooting structure
- Pas de configuration reference exhaustive (beaucoup d'options non documentees)
- Javadoc basique, non integree au site principal
- Pas de guide de securite
- Pas d'exemples de plugins intermediaires/avances
- Pas de diagrammes d'architecture
- Zero contenu video

**Verdict** : Infrastructure technique correcte, contenu insuffisant. Un admin doit fouiller GitHub Issues et Discord pour trouver des reponses.

### 1.2 Gate (Minekube)

**URL** : https://gate.minekube.com/

**Points forts :**
- Structure claire : Guide / Developers / Extensions
- Quick Start en 1 page
- Section OpenTelemetry detaillee (Grafana, Jaeger, Honeycomb)
- SDKs multi-langages (TypeScript, Python, Go, Rust, Kotlin, Java)
- Section securite (DDoS, cybersecurity)
- Configuration avec auto-reload documentee

**Points faibles :**
- Un seul exemple ("Simple Proxy") -- pas de progression pedagogique
- Pas de guide de migration depuis Velocity/BungeeCord
- Pas de reference API consolidee (eparpillee par langage)
- Pas de troubleshooting / FAQ
- Pas de guide de performance tuning
- Pas de diagrammes d'architecture
- Pas de documentation video

**Verdict** : Meilleure couverture que Velocity sur l'operationnel (observabilite, securite), mais lacunaire cote developpeur.

### 1.3 SpigotMC Wiki

**Points forts :**
- Enorme base de contenu communautaire accumulee sur 10+ ans
- Guides progressifs (plugin development from scratch)
- Javadoc complete

**Points faibles :**
- Wiki vieillissant, navigation chaotique
- Contenu souvent obsolete
- Pas de standard de qualite (contenu communautaire variable)
- Aucune recherche moderne

**Verdict** : Volume impressionnant, qualite inconsistante. Bon exemple de ce qu'il NE faut PAS faire (wiki ouvert sans curation).

### 1.4 Minestom

**Points forts :**
- Documentation claire pour une librairie (pas un produit fini)
- Javadoc accessible

**Points faibles :**
- Wiki archivee (derniere mise a jour juin 2024)
- Contenu souvent obsolete ("not always up to date")
- Dependance excessive sur Discord pour le support

**Verdict** : Symptomatique du probleme MC : la documentation meurt quand le mainteneur n'a plus le temps. Warp doit eviter ce piege.

### 1.5 Caddy (gold standard de la documentation proxy)

**URL** : https://caddyserver.com/docs/

**Points forts -- a reproduire :**
- **Parcours d'apprentissage multi-niveaux** : Tutorials -> Quick-starts -> Reference -> Articles
- **Plusieurs points d'entree** selon le niveau : debutant (Getting Started), intermediaire (Quick-starts), expert (Reference directe)
- **Double format de configuration** documente en parallele (Caddyfile humain + JSON programmatique)
- **Separation nette** : tutoriels vs reference vs articles conceptuels
- **Quick-starts specifiques** : fichiers statiques, reverse proxy, HTTPS, deploiement Railway
- **Integration communautaire** : wiki, forum, GitHub lies

**Lecon cle** : La documentation de Caddy est louee parce qu'elle respecte la regle des 3 niveaux (tutorial / how-to / reference) et que chaque page a un objectif clair.

### 1.6 Traefik

**URL** : https://doc.traefik.io/traefik/

**Points forts -- a reproduire :**
- **Organisation par persona** : Debutants / DevOps Engineers / Developpeurs
- **Flux progressif** : What is -> Features -> Getting Started -> Setup -> Expose -> Secure -> Observe -> Extend -> Migrate
- **Guides de migration depuis NGINX** et entre versions
- **Documentation de reference** separee (routing, securite, deprecation)
- **Construit avec MkDocs Material** -- recherche excellente

**Lecon cle** : L'organisation par verbes d'action (Setup, Expose, Secure, Observe, Extend, Migrate) est plus intuitive que par concepts abstraits.

### 1.7 Envoy

**URL** : https://www.envoyproxy.io/docs/

**Points forts -- a reproduire :**
- **Best practices par scenario de deploiement** (edge proxy, level 2 proxy)
- **Exemples de configuration complets** (bootstrap minimal fonctionnel)
- **Documentation versionee** (chaque release a ses propres docs)
- **Architecture documentation** extremement detaillee

**Lecon cle** : Envoy prouve qu'on peut documenter un projet complexe avec rigueur. Leur approche "architecture-first" est un modele.

---

## 2. PRINCIPES FONDATEURS {#2-principes-fondateurs}

Synthetises de l'audit, 8 principes guident la documentation Warp :

### Principe 1 : Documentation Quadripartite (Diataxis)

Chaque page appartient a exactement UNE categorie :

| Type | Objectif | Style | Exemple |
|------|----------|-------|---------|
| **Tutorial** | Apprendre par la pratique | Guidage pas-a-pas | "Premier plugin en 5 minutes" |
| **How-to** | Resoudre un probleme specifique | Oriente tache | "Migrer depuis Velocity" |
| **Reference** | Informer sur les details | Factuel, exhaustif | "Configuration reference" |
| **Explanation** | Comprendre un concept | Conceptuel, avec diagrammes | "Comment fonctionne le blind forwarding" |

### Principe 2 : Time-to-Value minimal

- **Admin** : proxy fonctionnel en < 5 minutes (Getting Started)
- **Developpeur** : premier plugin compile en < 5 minutes
- **Operateur** : dashboards Grafana en < 10 minutes

### Principe 3 : Documentation-as-Code

- Source en Markdown/MDX dans le meme monorepo
- PRs de docs revues comme du code
- CI teste les exemples de code, les liens, l'orthographe
- Deploy automatique sur merge

### Principe 4 : Aucune information orpheline

Tout ce qui est dans la Javadoc doit etre accessible depuis le site. Tout ce qui est dans le site doit pointer vers la Javadoc quand pertinent. Zero dependance sur Discord pour l'information critique.

### Principe 5 : Organisation par persona + action

Trois personas, trois parcours :

```
Admin       : Installer -> Configurer -> Deployer -> Securiser -> Monitorer -> Depanner
Developpeur : Comprendre -> Creer -> Tester -> Publier
Operateur   : Deployer -> Scaler -> Monitorer -> Automatiser
```

### Principe 6 : Exemples executables

Chaque snippet de code dans la documentation doit :
- Compiler (verifie en CI)
- Etre copie-collable (pas de `...` ou d'omissions implicites)
- Avoir un lien vers un exemple complet sur GitHub

### Principe 7 : Deprecation explicite

Quand une API change, l'ancienne documentation reste accessible avec un bandeau :
> Cette page documente une API deprecee. Voir [nouvelle API](/lien).

### Principe 8 : Accessibilite et inclusivite

- Conformite WCAG 2.1 AA minimum
- Alt text sur toutes les images/diagrammes
- Contenu lisible sans JavaScript (SSG)
- Contraste suffisant en dark mode ET light mode

---

## 3. CHOIX DE L'OUTILLAGE {#3-choix-de-loutillage}

### 3.1 Comparaison des generateurs

| Critere | **Starlight (Astro)** | Docusaurus (React) | MkDocs Material |
|---------|----------------------|--------------------|--------------------|
| **Performance** | Excellent (zero JS par defaut, island architecture) | Correct (bundle React ~200KB) | Excellent (HTML statique) |
| **i18n natif** | Oui (34 langues UI, routing, RTL) | Oui (Crowdin integre) | Partiel (plugin mkdocs-i18n) |
| **Versioning** | En discussion (pas natif, workaround possible) | Natif et mature | Natif via mike |
| **Recherche** | Pagefind integre (zero config) | Algolia DocSearch (ou local) | Lunr/Search integre |
| **MDX** | Oui | Oui | Non (Markdown pur + extensions) |
| **Composants interactifs** | Oui (React, Vue, Svelte via islands) | Oui (React uniquement) | Limite (HTML+JS inline) |
| **Theming** | Tailwind, CSS custom | Infima (rigide) | Material Design (configurable) |
| **Ecosysteme plugins** | Croissant (Astro integrations) | Mature | Tres riche (catalog de 300+) |
| **Courbe d'apprentissage** | Moyenne (Astro a apprendre) | Haute (React necessaire pour custom) | Faible (YAML + Markdown) |
| **Adopte par MC** | PaperMC (Velocity, Paper) | Non | Traefik |
| **Maintenance** | Active (Astro team, releases frequentes) | Active (Meta) | Active (squidfunk) |

### 3.2 Recommandation : Starlight (Astro)

**Justification :**

1. **Performance superieure** -- Zero JavaScript par defaut. Les pages se chargent instantanement. Crucial pour les admins sur des connexions mobiles ou dans des pays a faible bande passante.

2. **i18n natif de qualite** -- 34 langues prechargees, routing automatique, support RTL, fallback content. Essentiel pour la communaute MC mondiale.

3. **Pagefind integre** -- Recherche full-text sans service tiers, sans cout, sans tracking. Fonctionne offline. Alternative superieure a Algolia pour un projet open-source.

4. **Island Architecture** -- On peut integrer des composants React/Vue/Svelte pour les parties interactives (config builder, API explorer) sans alourdir les pages statiques.

5. **MDX natif** -- Permet d'embarquer des composants custom dans le Markdown (tabs de code, callouts, diagrammes interactifs).

6. **Coherence ecosysteme** -- PaperMC utilise deja Starlight. Les contributeurs MC connaissent l'outil. Facilite les contributions.

7. **Tailwind CSS** -- Theming flexible, dark mode natif, design system coherent.

**Risque identifie** : Le versioning n'est pas natif. Mitigation : Warp etant pre-1.0, le versioning n'est pas necessaire immediatement. D'ici la stabilisation, Starlight aura probablement le support natif (discussion active sur GitHub). En attendant, un workaround avec des branches Git + Cloudflare Pages par version est viable.

### 3.3 Recherche : Pagefind

| Solution | Cout | Latence | Offline | Poids | Tracking |
|----------|------|---------|---------|-------|----------|
| **Pagefind** | Gratuit | ~5ms | Oui | ~100KB index | Non |
| Algolia DocSearch | Gratuit (OSS) | ~20ms | Non | SDK ~30KB | Optionnel |
| FlexSearch | Gratuit | ~2ms | Oui | Custom | Non |

**Choix : Pagefind** -- Integre nativement a Starlight, zero configuration, zero dependance externe, fonctionne offline. Pour un projet open-source, c'est la solution ideale.

### 3.4 Hebergement : Cloudflare Pages

| Plateforme | Gratuit | CDN global | Preview deploys | Build time |
|------------|---------|------------|-----------------|------------|
| **Cloudflare Pages** | Oui (illimite) | Oui (300+ PoP) | Oui | Rapide |
| Vercel | Oui (limite) | Oui | Oui | Rapide |
| Netlify | Oui (limite) | Oui | Oui | Moyen |
| GitHub Pages | Oui | Partiel | Non natif | Variable |

**Choix : Cloudflare Pages** -- Gratuit sans limite, CDN mondial (important pour la communaute MC internationale), preview deploys sur chaque PR, integration Git native. Pas de vendor lock-in (static files deployables n'importe ou).

### 3.5 Javadoc

La Javadoc standard JDK 21 reste le meilleur choix pour la reference API Java :
- Generee automatiquement par `javadoc` en CI
- Deployee en sous-domaine : `javadoc.warp-proxy.dev`
- Lien bidirectionnel : site principal -> Javadoc et Javadoc -> site principal
- Theme custom possible via Javadoc doclet ou CSS override pour matcher le design Warp

Alternative envisagee et rejetee : Dokka (Kotlin-first, overhead inutile pour du Java pur).

---

## 4. ARBRE DE PAGES COMPLET {#4-arbre-de-pages-complet}

```
docs.warp-proxy.dev/
|
|-- /                                    # Landing page (hero + 3 parcours)
|
|-- /getting-started/                    # TUTORIALS (parcours guide)
|   |-- overview                         # Qu'est-ce que Warp? Pourquoi Warp?
|   |-- quickstart                       # Proxy fonctionnel en 5 minutes
|   |-- quickstart-docker                # Avec Docker (1 commande)
|   |-- first-plugin                     # Premier plugin en 5 minutes
|   |-- concepts                         # Blind forwarding, drain, pipeline
|   +-- next-steps                       # Ou aller ensuite (selon persona)
|
|-- /admin/                              # HOW-TO pour administrateurs
|   |-- installation/
|   |   |-- requirements                 # JDK 21+, OS, hardware
|   |   |-- jar                          # Telecharger et lancer le JAR
|   |   |-- docker                       # Image Docker officielle
|   |   |-- kubernetes                   # Helm chart, manifests K8s
|   |   |-- popular-hosts                # Pterodactyl, Pufferfish Host, Bloom, etc.
|   |   +-- building-from-source         # Compiler Warp soi-meme
|   |
|   |-- configuration/
|   |   |-- overview                     # Fichier de config, format, hot reload
|   |   |-- reference                    # CHAQUE option documentee (auto-generee)
|   |   |-- servers                      # Configurer les serveurs backend
|   |   |-- forwarding                   # Modes de forwarding (modern, legacy, none)
|   |   |-- compression                  # Seuils, algorithmes, blind forwarding
|   |   |-- rate-limiting                # Limites par IP, par type
|   |   +-- advanced                     # System properties, JVM flags
|   |
|   |-- deployment/
|   |   |-- bare-metal                   # Linux, systemd, supervision
|   |   |-- docker-compose               # Compose avec backends
|   |   |-- kubernetes                   # Deployments, services, HPA, probes
|   |   |-- behind-haproxy               # HAProxy + PROXY protocol
|   |   |-- behind-nginx                 # NGINX TCP proxy
|   |   |-- behind-cloudflare            # Cloudflare Spectrum / TCPShield
|   |   +-- multi-proxy                  # Scaling horizontal, Redis sync
|   |
|   |-- migration/
|   |   |-- from-velocity                # Guide pas-a-pas avec diff de configs
|   |   |-- from-bungeecord              # Guide pas-a-pas, plugins equivalents
|   |   |-- from-waterfall               # Specifique Waterfall -> Warp
|   |   |-- from-gate                    # Specifique Gate -> Warp
|   |   +-- plugin-compatibility         # Tableau de compatibilite des plugins MC
|   |
|   |-- security/
|   |   |-- overview                     # Modele de securite de Warp
|   |   |-- hardening                    # Guide de durcissement complet
|   |   |-- forwarding-security          # HMAC, secrets par serveur, rotation
|   |   |-- anti-bot                     # Protection anti-bot native
|   |   |-- anti-ddos                    # Rate limiting, detection, mitigation
|   |   |-- firewall-rules               # iptables/nftables recommandes
|   |   +-- audit-log                    # Logging des evenements de securite
|   |
|   |-- monitoring/
|   |   |-- overview                     # Philosophie d'observabilite
|   |   |-- prometheus                   # Metriques exposees, scrape config
|   |   |-- grafana                      # Dashboards preconfigures (JSON)
|   |   |-- opentelemetry                # Tracing distribue
|   |   |-- logging                      # Structured logging, niveaux, rotation
|   |   |-- health-checks               # Probes HTTP, HAProxy agent-check
|   |   |-- alerting                     # Regles d'alerte recommandees
|   |   +-- jfr                          # Java Flight Recorder pour profiling
|   |
|   |-- operations/
|   |   |-- drain                        # Drain multi-phase, zero-downtime
|   |   |-- rolling-update               # Mise a jour sans interruption
|   |   |-- backup-restore               # Sauvegardes de configuration
|   |   |-- performance-tuning           # Guide d'optimisation complet
|   |   +-- capacity-planning            # Sizing, benchmarks, projections
|   |
|   |-- troubleshooting/
|   |   |-- common-issues                # Problemes frequents + solutions
|   |   |-- connection-issues            # Debug des connexions
|   |   |-- performance-issues           # Debug des performances
|   |   |-- plugin-conflicts             # Conflits entre plugins
|   |   |-- faq                          # Questions frequentes
|   |   +-- getting-help                 # Discord, GitHub, comment reporter un bug
|
|-- /dev/                                # HOW-TO + TUTORIALS pour developpeurs
|   |-- getting-started/
|   |   |-- overview                     # L'API Warp en 2 minutes
|   |   |-- environment-setup            # JDK 21, Gradle, IDE
|   |   |-- first-plugin                 # Hello World plugin (tutorial complet)
|   |   +-- project-structure            # Anatomie d'un plugin Warp
|   |
|   |-- guides/
|   |   |-- events                       # Systeme d'evenements (ecouter, creer)
|   |   |-- commands                     # Enregistrer des commandes
|   |   |-- configuration                # API de configuration pour plugins
|   |   |-- scheduling                   # Taches planifiees, async
|   |   |-- players                      # API Player (kick, transfer, messages)
|   |   |-- servers                      # API Server (register, status, switch)
|   |   |-- packets                      # Interception et envoi de paquets
|   |   |-- permissions                  # Systeme de permissions
|   |   |-- dependency-injection         # DI avec Avaje Inject
|   |   |-- services                     # Services partages entre plugins
|   |   +-- protocol                     # Travailler avec le protocole MC
|   |
|   |-- examples/
|   |   |-- simple-motd                  # Plugin debutant : MOTD custom
|   |   |-- join-messages                # Plugin debutant : messages de connexion
|   |   |-- server-selector              # Plugin intermediaire : menu de serveurs
|   |   |-- chat-bridge                  # Plugin intermediaire : chat cross-server
|   |   |-- queue-system                 # Plugin avance : file d'attente
|   |   |-- custom-matchmaking           # Plugin avance : matchmaking
|   |   +-- packet-inspector             # Plugin avance : inspection de paquets
|   |
|   |-- testing/
|   |   |-- overview                     # Philosophie de test pour plugins
|   |   |-- mockwarp                     # Framework MockWarp (unit tests)
|   |   |-- integration-tests            # Tests d'integration avec vrai proxy
|   |   +-- ci-setup                     # GitHub Actions pour plugins
|   |
|   |-- publishing/
|   |   |-- overview                     # Ou et comment publier
|   |   |-- modrinth                     # Publier sur Modrinth
|   |   |-- hangar                       # Publier sur Hangar
|   |   |-- maven-central                # Publier une librairie sur Maven Central
|   |   +-- github-releases              # CI/CD avec GitHub Actions
|   |
|   |-- migration/
|   |   |-- from-velocity-api            # Adapter un plugin Velocity pour Warp
|   |   |-- from-bungeecord-api          # Adapter un plugin BungeeCord pour Warp
|   |   +-- api-changelog                # Historique des changements d'API
|
|-- /architecture/                       # EXPLANATIONS (comprendre Warp)
|   |-- overview                         # Vision haute (diagramme C4 Context)
|   |-- pipeline                         # Pipeline de paquets (diagramme)
|   |-- blind-forwarding                 # Comment et pourquoi le blind forwarding
|   |-- connection-lifecycle             # Du handshake au play state
|   |-- drain-system                     # Drain multi-phase explique
|   |-- event-system                     # Architecture du systeme d'evenements
|   |-- plugin-loading                   # Chargement, isolation, classloaders
|   |-- protocol                         # Protocole MC (versions, etats, paquets)
|   |-- networking                       # Netty, epoll, io_uring, channels
|   |-- security-model                   # Modele de menaces et defenses
|   |-- adr/                             # Architecture Decision Records
|   |   |-- 001-java-over-go-rust        # Pourquoi Java et pas Go/Rust
|   |   |-- 002-blind-forwarding         # Blind forwarding vs full parsing
|   |   |-- 003-avaje-over-guice         # Avaje Inject vs Google Guice
|   |   |-- 004-pf4j-classloaders        # Isolation des plugins
|   |   |-- 005-netty-over-grizzly       # Pourquoi Netty
|   |   |-- 006-micrometer-metrics       # Micrometer vs OpenTelemetry Metrics
|   |   |-- 007-toml-configuration       # TOML vs YAML vs HOCON
|   |   |-- 008-starlight-docs           # Choix de Starlight pour les docs
|   |   |-- 009-hmac-forwarding          # HMAC-SHA256 pour le forwarding
|   |   |-- 010-drain-transfer-packet    # Transfer Packet pour le drain
|   |   +-- template                     # Template ADR pour le futur
|   |
|   +-- diagrams/                        # Fichiers source Mermaid
|       |-- c4-context                   # Diagramme C4 niveau 1
|       |-- c4-container                 # Diagramme C4 niveau 2
|       |-- c4-component                 # Diagramme C4 niveau 3
|       |-- packet-pipeline              # Pipeline de traitement des paquets
|       |-- connection-states            # Machine a etats des connexions
|       +-- drain-sequence               # Sequence de drain
|
|-- /reference/                          # REFERENCE (exhaustif, factuel)
|   |-- configuration                    # Reference complete auto-generee
|   |-- cli                              # Arguments de ligne de commande
|   |-- api/                             # Lien vers javadoc.warp-proxy.dev
|   |-- events                           # Tous les evenements documentes
|   |-- permissions                      # Toutes les permissions
|   |-- placeholders                     # Tous les placeholders
|   |-- protocol/
|   |   |-- versions-supported           # Versions MC supportees
|   |   |-- packets                      # Paquets interceptables par les plugins
|   |   +-- states                       # Etats de connexion
|   |-- metrics                          # Toutes les metriques Prometheus
|   |-- system-properties                # Proprietes systeme JVM
|   +-- glossary                         # Glossaire des termes Warp/MC
|
|-- /community/
|   |-- contributing                     # Guide de contribution
|   |-- code-of-conduct                  # Code de conduite
|   |-- changelog                        # Notes de version
|   |-- roadmap                          # Feuille de route publique
|   +-- acknowledgments                  # Credits et remerciements
|
+-- /blog/                              # Annonces, deep-dives techniques
    |-- introducing-warp                 # Article de lancement
    |-- blind-forwarding-benchmarks      # Benchmarks detailles
    +-- ...                              # Articles futurs
```

**Total : ~120 pages** au lancement complet, ~40 pages pour le MVP.

---

## 5. DOCUMENTATION INTERACTIVE {#5-documentation-interactive}

### 5.1 Configuration Builder

Un outil interactif qui genere un fichier `warp.toml` valide :

```
[Interface web dans la page /admin/configuration/builder]

1. L'utilisateur repond a des questions simples :
   - Combien de serveurs backend ? [1-50]
   - Mode de forwarding ? [Modern (recommande) / Legacy / None]
   - Compression activee ? [Oui / Non]
   - Protection anti-bot ? [Oui / Non]
   - Monitoring Prometheus ? [Oui / Non]

2. Le builder genere le TOML en temps reel avec commentaires
3. Bouton "Copier" / "Telecharger warp.toml"
4. Lien vers la documentation de chaque option
```

**Implementation** : Composant Astro (island) avec Solid.js ou Preact (leger). Pas de backend necessaire -- tout cote client.

### 5.2 API Explorer

Une page interactive pour explorer l'API Java Warp :

```
[Interface web dans la page /dev/api-explorer]

1. Navigation arborescente des packages
2. Recherche instantanee (classes, methodes, champs)
3. Exemples de code inline pour chaque classe importante
4. Lien vers la Javadoc complete
```

**Implementation** : JSON genere depuis la Javadoc en CI, interface Astro + Pagefind sur les donnees API.

### 5.3 Code Snippets Executables

Pour les exemples de plugins, integration de **Codapi** (sandboxes cote serveur) ou a defaut, liens directs vers **GitHub Codespaces** pre-configures :

```
// Exemple dans la documentation
@WarpPlugin(id = "hello", name = "Hello Plugin")
public class HelloPlugin {
    @Subscribe
    public void onLogin(PlayerLoginEvent event) {
        event.player().sendMessage(
            Component.text("Welcome to Warp!")
        );
    }
}

[> Essayer dans Codespace] [> Voir le projet complet sur GitHub]
```

**Realisme** : Java n'est pas executable dans le navigateur comme JS. On privilegie donc :
- Code syntaxiquement valide (verifie en CI)
- Bouton "Ouvrir dans GitHub Codespace" pre-configure
- Repo `warp-examples` avec tous les exemples compilables

### 5.4 Packet Inspector (futur)

Page interactive pour visualiser les paquets du protocole MC :

```
[Interface dans /architecture/protocol]

1. Selection d'un etat (Handshake, Login, Configuration, Play)
2. Liste des paquets avec filtre
3. Pour chaque paquet : structure binaire, champs, types, description
4. Visualisation du pipeline : Client -> Proxy (blind?) -> Backend
```

**Implementation** : Genere depuis le code source de Warp (annotations sur les paquets). Priorite basse (post-1.0).

---

## 6. ARCHITECTURE DECISION RECORDS {#6-architecture-decision-records}

### 6.1 Template ADR (format MADR adapte)

```markdown
# ADR-XXX : [Titre de la decision]

**Date** : YYYY-MM-DD
**Statut** : Proposee | Acceptee | Deprecee | Remplacee par ADR-YYY

## Contexte

[Quel probleme resolvons-nous ? Quelles contraintes existent ?]

## Options considerees

### Option A : [Nom]
- (+) Avantage 1
- (+) Avantage 2
- (-) Inconvenient 1

### Option B : [Nom]
- (+) Avantage 1
- (-) Inconvenient 1
- (-) Inconvenient 2

## Decision

Nous choisissons **Option X** parce que [justification concise].

## Consequences

- [Consequence positive 1]
- [Consequence negative acceptee 1]
- [Action requise 1]
```

### 6.2 ADRs prevues pour Warp

| # | Titre | Priorite |
|---|-------|----------|
| 001 | Java 21 plutot que Go ou Rust | Haute |
| 002 | Blind forwarding par defaut | Haute |
| 003 | Avaje Inject plutot que Guice | Haute |
| 004 | PF4J + classloaders isoles | Haute |
| 005 | Netty avec io_uring | Moyenne |
| 006 | Micrometer pour les metriques | Moyenne |
| 007 | TOML pour la configuration | Moyenne |
| 008 | Starlight pour la documentation | Moyenne |
| 009 | HMAC-SHA256 forwarding (compatible Velocity) | Haute |
| 010 | Transfer Packet pour le drain | Haute |
| 011 | Systeme d'evenements avec garanties d'ordre | Haute |
| 012 | Machine a etats pour les connexions | Haute |
| 013 | Canal headless proxy-backend | Moyenne |
| 014 | Pagination et streaming de la config reference | Basse |

---

## 7. DIAGRAMMES D'ARCHITECTURE (C4) {#7-diagrammes-darchitecture-c4}

### 7.1 Outil : Mermaid

**Justification :**
- Natif dans GitHub (rendu automatique dans les README/Issues)
- Supporte C4 (experimental mais fonctionnel)
- Integrable dans Starlight via plugin remark
- Versionne en texte (diff lisible dans les PRs)
- Pas de dependance sur un outil externe (vs PlantUML qui necessite Java)

### 7.2 Diagrammes prevus

| Diagramme | Niveau C4 | Contenu |
|-----------|-----------|---------|
| System Context | C1 | Warp + Joueurs + Backends + Monitoring + Redis |
| Container | C2 | Proxy Core + Plugin System + Admin API + Metrics |
| Component (Core) | C3 | Pipeline, Connection Manager, Server Registry, Drain |
| Component (Plugin) | C3 | Classloader, Event Bus, Command Registry, Config |
| Packet Pipeline | Custom | Client -> Decode -> Route -> (Blind/Inspect) -> Encode -> Backend |
| Connection States | Custom | Machine a etats Handshake -> Login -> Config -> Play |
| Drain Sequence | Custom | Diagramme de sequence drain multi-phase |
| Deployment (K8s) | Deployment | Pods, Services, HPA, Probes, Redis |

### 7.3 Integration dans Starlight

```astro
---
// Composant MermaidDiagram.astro
---
<div class="mermaid" set:html={Astro.slots.render('default')} />
<script>
  import mermaid from 'mermaid';
  mermaid.initialize({ startOnLoad: true, theme: 'dark' });
</script>
```

Chaque diagramme est :
1. Defini en Mermaid dans un fichier `.mmd` source
2. Integre dans la page MDX via le composant
3. Exporte aussi en PNG/SVG pour les README GitHub

---

## 8. INTERNATIONALISATION {#8-internationalisation}

### 8.1 Strategie linguistique

| Phase | Langues | Justification |
|-------|---------|---------------|
| **MVP** | Anglais uniquement | Focus sur la qualite du contenu source |
| **Post-1.0** | + Francais | Langue maternelle du mainteneur principal |
| **Communaute** | + Chinois, Espagnol, Allemand, Portugais, Russe | Top langues de la communaute MC par volume |
| **Futur** | + Japonais, Coreen | Communautes MC actives en Asie |

**Donnees** : Minecraft supporte 100+ langues sur Crowdin. Les communautes MC les plus actives sont EN, ZH, ES, DE, PT-BR, RU, FR, JA, KO, PL (dans cet ordre approximatif par volume de joueurs/serveurs).

### 8.2 Outil de traduction : Crowdin

**Justification :**
- Gratuit pour les projets open-source
- Utilise par Minecraft lui-meme (familier pour les contributeurs MC)
- Integration native avec GitHub (sync bi-directionnelle)
- Interface web accessible aux non-developpeurs
- Memoire de traduction et glossaire partages

**Workflow :**
```
1. Mainteneur ecrit/modifie du contenu en anglais
2. Push sur main declenche la sync Crowdin
3. Traducteurs voient les changements dans l'interface Crowdin
4. Traductions approuvees declenchent une PR automatique
5. PR mergee -> deploy des docs traduites
```

### 8.3 Integration Starlight + Crowdin

Starlight supporte nativement le i18n avec :
- Routing automatique (`/fr/getting-started/`, `/zh/getting-started/`)
- Fallback au contenu anglais si traduction manquante
- Banniere "Cette page n'est pas encore traduite" automatique
- Selecteur de langue dans le header

Configuration Starlight :
```js
// astro.config.mjs
export default defineConfig({
  integrations: [
    starlight({
      locales: {
        root: { label: 'English', lang: 'en' },
        fr: { label: 'Francais', lang: 'fr' },
        zh: { label: '中文', lang: 'zh-CN' },
        es: { label: 'Espanol', lang: 'es' },
      },
    }),
  ],
});
```

---

## 9. VIDEO ET MULTIMEDIA {#9-video-et-multimedia}

### 9.1 Strategie video

| Type | Format | Plateforme | Integration docs |
|------|--------|------------|------------------|
| **Quickstart** (< 5 min) | Screencast + voix | YouTube | Embed iframe dans /getting-started/ |
| **Architecture walkthrough** (10-15 min) | Slides + diagrammes animes | YouTube | Embed dans /architecture/ |
| **Plugin tutorial** (5-10 min) | IDE screencast + voix | YouTube | Embed dans /dev/guides/ |
| **Release notes** (2-3 min) | Screencast rapide | YouTube | Embed dans /blog/ et /community/changelog |
| **Deep dive** (20-30 min) | Presentation technique | YouTube | Lien depuis /architecture/ |

### 9.2 Principes de production

- **Chaque video a une page texte equivalente** -- la video est un complement, jamais le seul format
- **Sous-titres anglais obligatoires** (auto-generes + corriges)
- **Chapitrage YouTube** pour navigation rapide
- **Pas de musique** -- juste la voix et l'ecran
- **Mise a jour** : si une video devient obsolete, un overlay "outdated" + lien vers la nouvelle version

### 9.3 Outils de production

- **Enregistrement** : OBS Studio (gratuit, open-source)
- **Montage** : Kdenlive ou DaVinci Resolve (gratuit)
- **Diagrammes animes** : Excalidraw ou Mermaid export
- **Hebergement** : YouTube (gratuit, CDN mondial, sous-titres auto)

---

## 10. CI/CD POUR LA DOCUMENTATION {#10-cicd-pour-la-documentation}

### 10.1 Pipeline GitHub Actions

```yaml
# .github/workflows/docs.yml
name: Documentation CI

on:
  push:
    branches: [main]
    paths: ['docs/**']
  pull_request:
    paths: ['docs/**']

jobs:
  # Job 1 : Build et validation
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with: { node-version: 22 }

      - name: Install dependencies
        run: npm ci
        working-directory: docs

      - name: Build site (includes link validation)
        run: npm run build
        working-directory: docs

      - name: Upload build artifact
        uses: actions/upload-artifact@v4
        with:
          name: docs-site
          path: docs/dist

  # Job 2 : Verification orthographique
  spellcheck:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: check-spelling/check-spelling@v0.0.22
        with:
          config: docs/.spellcheck
          extra_dictionaries: |
            cspell:java/dict/java.txt
            cspell:gaming-terms/dict/gaming-terms.txt

  # Job 3 : Verification des liens
  linkcheck:
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/download-artifact@v4
        with: { name: docs-site }
      - name: Check links
        uses: lycheeverse/lychee-action@v2
        with:
          args: '--verbose --no-progress ./dist'
          fail: true

  # Job 4 : Compilation des exemples de code Java
  verify-code-examples:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Extract and compile code examples
        run: |
          # Script qui extrait les blocs ```java des .md/.mdx
          # et les compile avec javac + classpath Warp API
          ./scripts/verify-doc-examples.sh

  # Job 5 : Accessibilite (sur PR uniquement)
  accessibility:
    runs-on: ubuntu-latest
    needs: build
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/download-artifact@v4
        with: { name: docs-site }
      - name: Pa11y accessibility check
        run: npx pa11y-ci --config .pa11yci.json

  # Job 6 : Deploy (sur push main uniquement)
  deploy:
    runs-on: ubuntu-latest
    needs: [build, spellcheck, linkcheck, verify-code-examples]
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/download-artifact@v4
        with: { name: docs-site }
      - name: Deploy to Cloudflare Pages
        uses: cloudflare/wrangler-action@v3
        with:
          command: pages deploy dist --project-name=warp-docs
          apiToken: ${{ secrets.CF_API_TOKEN }}
```

### 10.2 Preview Deploys

Chaque PR touchant `docs/` declenche automatiquement un deploy preview sur Cloudflare Pages :
- URL unique par PR : `pr-123.warp-docs.pages.dev`
- Commentaire automatique sur la PR avec le lien preview
- Permet la revue visuelle avant merge

### 10.3 Verification des exemples de code

Script `verify-doc-examples.sh` :

```bash
#!/bin/bash
# Extrait les blocs ```java des fichiers .md/.mdx
# Genere des fichiers .java temporaires
# Compile avec javac -cp warp-api.jar
# Echoue si un exemple ne compile pas

set -euo pipefail

WARP_API_JAR="build/libs/warp-api-*.jar"
TEMP_DIR=$(mktemp -d)
ERRORS=0

# Extraire les blocs Java des fichiers Markdown
find docs/src/content -name "*.md" -o -name "*.mdx" | while read file; do
  awk '/^```java/,/^```/' "$file" | grep -v '^```' > "$TEMP_DIR/$(basename $file).java"
done

# Compiler chaque fichier
for java_file in "$TEMP_DIR"/*.java; do
  if [ -s "$java_file" ]; then
    javac -cp "$WARP_API_JAR" "$java_file" 2>/dev/null || {
      echo "ERREUR: Exemple non compilable dans $java_file"
      ((ERRORS++))
    }
  fi
done

exit $ERRORS
```

### 10.4 Javadoc CI

```yaml
# Dans le workflow principal du projet (pas seulement docs)
javadoc:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with: { distribution: temurin, java-version: 21 }
    - name: Generate Javadoc
      run: ./gradlew javadoc
    - name: Deploy Javadoc
      uses: cloudflare/wrangler-action@v3
      with:
        command: pages deploy api/build/docs/javadoc --project-name=warp-javadoc
```

---

## 11. PRIORITES DE CONTENU {#11-priorites-de-contenu}

### Phase 1 : MVP Documentation (avant premiere release publique)

**Objectif** : Un admin peut installer et operer Warp, un dev peut creer un plugin basique.

| # | Page | Persona | Justification |
|---|------|---------|---------------|
| 1 | Landing page | Tous | Premiere impression, 3 parcours |
| 2 | /getting-started/overview | Tous | Comprendre ce qu'est Warp |
| 3 | /getting-started/quickstart | Admin | Time-to-value < 5 min |
| 4 | /getting-started/quickstart-docker | Admin | Option Docker rapide |
| 5 | /admin/configuration/reference | Admin | L'option qu'ils cherchent DOIT etre documentee |
| 6 | /admin/configuration/servers | Admin | Configurer les backends |
| 7 | /admin/configuration/forwarding | Admin | Securite critique |
| 8 | /admin/migration/from-velocity | Admin | Audience #1 : ceux qui migrent |
| 9 | /admin/migration/from-bungeecord | Admin | Audience #2 : ceux qui migrent |
| 10 | /admin/troubleshooting/faq | Admin | Reduire la charge support Discord |
| 11 | /dev/getting-started/first-plugin | Dev | Time-to-value < 5 min |
| 12 | /dev/guides/events | Dev | Fonctionnalite #1 des plugins proxy |
| 13 | /dev/guides/commands | Dev | Fonctionnalite #2 des plugins proxy |
| 14 | /reference/configuration | Tous | Auto-genere, exhaustif |
| 15 | /reference/events | Dev | Auto-genere, exhaustif |
| 16 | /community/contributing | Tous | Attirer les contributeurs |
| 17 | Javadoc complete | Dev | Reference API |

**Volume estime** : ~40 pages, ~15000-20000 mots

### Phase 2 : Documentation operationnelle (post premiere release)

| # | Page | Priorite |
|---|------|----------|
| 18 | /admin/deployment/* (tous) | Haute |
| 19 | /admin/security/* (tous) | Haute |
| 20 | /admin/monitoring/* (tous) | Haute |
| 21 | /admin/operations/drain | Haute |
| 22 | /admin/operations/rolling-update | Haute |
| 23 | /architecture/overview | Moyenne |
| 24 | /architecture/blind-forwarding | Moyenne |
| 25 | /architecture/pipeline | Moyenne |
| 26 | /dev/guides/* (reste) | Moyenne |
| 27 | /dev/examples/* (3 premiers) | Moyenne |
| 28 | /dev/testing/mockwarp | Moyenne |
| 29 | ADRs 001-005 | Moyenne |

**Volume estime** : +40 pages, +20000 mots

### Phase 3 : Documentation avancee (pre-1.0)

| # | Page | Priorite |
|---|------|----------|
| 30 | /architecture/* (tous) | Moyenne |
| 31 | /admin/operations/performance-tuning | Moyenne |
| 32 | /admin/deployment/kubernetes (avance) | Moyenne |
| 33 | /dev/examples/* (reste) | Moyenne |
| 34 | /dev/publishing/* | Moyenne |
| 35 | /dev/migration/* | Moyenne |
| 36 | Configuration Builder interactif | Moyenne |
| 37 | ADRs restants | Basse |
| 38 | /reference/protocol/* | Basse |
| 39 | Blog de lancement | Haute |
| 40 | Premieres videos | Basse |

### Phase 4 : Excellence (post-1.0)

- Internationalisation (francais, puis communaute)
- API Explorer interactif
- Packet Inspector
- Videos completes (quickstart, architecture, plugins)
- Documentation versionee (si Starlight le supporte)

---

## 12. CALENDRIER DE LIVRAISON {#12-calendrier-de-livraison}

### Prerequis : Infrastructure (Semaine 0)

| Tache | Duree estimee |
|-------|---------------|
| Init projet Starlight dans `docs/site/` | 2h |
| Theme custom (couleurs, logo, typographie) | 4h |
| Pipeline CI/CD (GitHub Actions + Cloudflare Pages) | 4h |
| Spellcheck + linkcheck + Pa11y config | 2h |
| Script verify-doc-examples.sh | 3h |
| Javadoc CI + deploy | 2h |
| **Total infrastructure** | **~2 jours** |

### Phase 1 : MVP Documentation

| Tache | Duree estimee | Dependance |
|-------|---------------|------------|
| Landing page | 3h | Theme custom |
| Getting Started (3 pages) | 6h | Proxy fonctionnel |
| Config reference (auto-gen) | 4h | Schema de config stable |
| Config guides (3 pages) | 6h | Config reference |
| Migration guides (2 pages) | 8h | API stable |
| FAQ + Troubleshooting | 4h | Retours early adopters |
| First Plugin tutorial | 6h | API plugin stable |
| Events + Commands guides | 6h | API plugin stable |
| Contributing guide | 2h | - |
| Javadoc review + deploy | 3h | API stable |
| **Total Phase 1** | **~6 jours** |

### Phase 2 : Documentation operationnelle

| Tache | Duree estimee |
|-------|---------------|
| Deployment guides (6 pages) | 12h |
| Security guides (6 pages) | 10h |
| Monitoring guides (7 pages) | 12h |
| Operations guides (3 pages) | 6h |
| Architecture pages (3 pages) | 8h |
| Dev guides restants (6 pages) | 10h |
| Exemples plugins (3 premiers) | 8h |
| MockWarp guide | 4h |
| ADRs 001-005 | 5h |
| **Total Phase 2** | **~10 jours** |

### Phase 3 : Documentation avancee

| Tache | Duree estimee |
|-------|---------------|
| Architecture completes | 8h |
| Performance tuning | 4h |
| K8s avance | 4h |
| Exemples plugins restants | 8h |
| Publishing guides | 4h |
| Dev migration guides | 6h |
| Config Builder interactif | 12h |
| ADRs restants | 5h |
| Reference protocol | 6h |
| Blog de lancement | 4h |
| **Total Phase 3** | **~8 jours** |

### Phase 4 : Excellence (continu)

| Tache | Duree estimee |
|-------|---------------|
| Setup Crowdin + francais | 4h |
| Premiere video (quickstart) | 8h |
| API Explorer | 16h |
| Traductions communautaires | Continu |
| Videos additionnelles | Continu |

---

## RECAPITULATIF DES DECISIONS

| Decision | Choix | Alternative rejetee | Raison |
|----------|-------|--------------------|---------|
| Generateur de site | **Starlight (Astro)** | Docusaurus, MkDocs | Performance, i18n natif, coherence ecosysteme MC |
| Recherche | **Pagefind** | Algolia, FlexSearch | Zero config, gratuit, offline, integre a Starlight |
| Hebergement | **Cloudflare Pages** | Vercel, Netlify, GH Pages | Gratuit illimite, CDN mondial, preview deploys |
| Diagrammes | **Mermaid** | PlantUML, Draw.io | Natif GitHub, versionnable, integrable docs |
| Traduction | **Crowdin** | Weblate, Transifex | Gratuit OSS, familier MC, integration GitHub |
| Javadoc | **JDK 21 standard** | Dokka | Java natif, pas de dependance supplementaire |
| ADR format | **MADR adapte** | Nygard, Y-Statement | Structure complete mais concise |
| Verification code | **Script CI custom** | Aucun | Garantit que chaque exemple compile |
| Spell check | **check-spelling GH Action** | PySpelling, codespell | Natif GitHub, dictionnaires custom |
| Link check | **Lychee** | htmltest, broken-link-checker | Rapide (Rust), configurable, maintenu |
| Accessibilite | **Pa11y** | axe-core, Lighthouse | CI-friendly, WCAG configurable |

---

## CE QUI RENDRA LA DOC WARP SUPERIEURE

### vs Velocity
- **Configuration reference exhaustive** (chaque option, pas juste les "essentielles")
- **Guides de migration concrets** (pas juste "utilisez Warp")
- **Guides de deploiement reels** (Docker, K8s, bare metal, popular hosts)
- **Troubleshooting structure** (pas "allez sur Discord")
- **Exemples de plugins progressifs** (debutant -> avance)
- **Architecture documentee** (diagrammes, ADRs)
- **Videos**

### vs Gate
- **Progression pedagogique** (7 exemples gradues vs 1 seul)
- **Guides de migration** (Gate n'en a pas)
- **Troubleshooting / FAQ**
- **Documentation en francais** (niche mais differenciateur)
- **Contenu video**

### vs Tout le monde
- **Exemples de code verifies en CI** (aucun proxy MC ne fait ca)
- **Configuration Builder interactif**
- **ADRs publics** (transparence des decisions techniques)
- **Diagrammes C4** (aucun proxy MC n'a de documentation d'architecture formelle)
- **Tests d'accessibilite en CI**
- **Internationalisation avec Crowdin** (communautaire, scalable)
- **Quadripartite Diataxis** (tutorial / how-to / reference / explanation clairement separes)
