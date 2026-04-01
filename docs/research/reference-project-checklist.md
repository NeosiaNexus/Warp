# Checklist : Faire de Warp un projet Java "reference"

**Date** : 31 mars 2026
**Objectif** : Identifier et prioriser chaque pratique qui fait d'un projet open-source Java un standard dans son ecosysteme.
**Methode** : Analyse croisee de Netty, Minestom, Quarkus, Vert.x, JUnit 5, et des meilleures pratiques de l'industrie.

---

## Table des matieres

1. [Structure du projet et build Gradle](#1-structure-du-projet-et-build-gradle)
2. [Qualite du code](#2-qualite-du-code)
3. [Null safety](#3-null-safety)
4. [Design d'API](#4-design-dapi)
5. [Documentation](#5-documentation)
6. [CI/CD](#6-cicd)
7. [Tests et benchmarks](#7-tests-et-benchmarks)
8. [Gestion des releases](#8-gestion-des-releases)
9. [Communaute](#9-communaute)
10. [Licence et legal](#10-licence-et-legal)

---

## 1. Structure du projet et build Gradle

### 1.1 Organisation multi-module

Les projets de reference (Netty, Minestom, Quarkus) partagent une structure modulaire claire.

**Actions :**
- [ ] Definir des modules distincts avec des responsabilites isolees :
  - `warp-api` — API publique (interfaces, annotations, events, types)
  - `warp-protocol` — Lecture/ecriture de paquets Minecraft, codec, compression
  - `warp-proxy` — Implementation du proxy (routing, pipeline, event bus)
  - `warp-plugin-api` — SPI pour les plugins (extension points, lifecycle)
  - `warp-common` — Utilitaires partages (collections, config, logging)
  - `warp-testing` — Framework de test pour les developpeurs de plugins
  - `warp-cli` — Interface en ligne de commande
- [ ] Separer strictement API et implementation (les utilisateurs dependent de `warp-api`, jamais de `warp-proxy`)
- [ ] Publier un BOM (Bill of Materials) `warp-bom` pour aligner les versions

### 1.2 Convention plugins Gradle

Le mecanisme recommande pour partager la logique de build. `buildSrc` est deconseille au profit d'un included build.

**Actions :**
- [ ] Creer un dossier `build-logic/` avec un included build :
  ```
  build-logic/
    src/main/kotlin/
      warp.java-conventions.gradle.kts    # Java toolchain, encoding, compile options
      warp.library-conventions.gradle.kts  # Pour les modules publies (javadoc, signing)
      warp.testing-conventions.gradle.kts  # JUnit 5, JaCoCo, config partagee
  ```
- [ ] Dans `settings.gradle.kts` : `includeBuild("build-logic")`
- [ ] Chaque module applique un convention plugin au lieu de repeter la config

### 1.3 Version catalog

**Actions :**
- [ ] Utiliser `gradle/libs.versions.toml` pour toutes les dependances
- [ ] Grouper les dependances liees en bundles (ex: `netty`, `testing`, `logging`)
- [ ] Centraliser les versions des plugins Gradle dans `[plugins]`

### 1.4 Caches et performance du build

**Actions :**
- [ ] Activer le build cache : `org.gradle.caching=true` dans `gradle.properties`
- [ ] Activer la configuration cache : `org.gradle.configuration-cache=true`
- [ ] Encoder en UTF-8 : `org.gradle.jvmargs` + `systemProp.file.encoding=UTF-8`
- [ ] Activer le build parallele : `org.gradle.parallel=true`
- [ ] Viser la compatibilite configuration cache de Gradle 9+

### 1.5 Fichiers racine

Tout projet de reference possede ces fichiers a la racine (cf. Netty, Minestom, Quarkus) :

- [ ] `LICENSE` (texte complet)
- [ ] `NOTICE` (attributions tierces)
- [ ] `README.md` (badges, quickstart, liens)
- [ ] `CONTRIBUTING.md`
- [ ] `CODE_OF_CONDUCT.md`
- [ ] `SECURITY.md` (politique de divulgation de vulnerabilites)
- [ ] `.editorconfig` (indentation, encoding, EOL)
- [ ] `.gitattributes` (normalisation des fins de ligne)
- [ ] `.gitignore`
- [ ] `gradle.properties`
- [ ] `settings.gradle.kts`
- [ ] `build.gradle.kts`

---

## 2. Qualite du code

### 2.1 Formatage automatique

Le formatage ne doit jamais faire l'objet d'un debat en code review.

**Actions :**
- [ ] Integrer **Spotless** avec **Palantir Java Format** (fork de google-java-format, 120 colonnes, optimise pour lambdas et streams)
  ```kotlin
  // build-logic convention plugin
  spotless {
      java {
          palantirJavaFormat()
          importOrder("", "javax|java", "\\#")
          removeUnusedImports()
          trimTrailingWhitespace()
          endWithNewline()
      }
  }
  ```
- [ ] `spotlessCheck` dans le CI (echec si code non formate)
- [ ] `spotlessApply` en pre-commit hook local (optionnel mais recommande)
- [ ] Documenter la config IDE (IntelliJ : plugin Palantir Java Format)

### 2.2 Analyse statique

Combiner plusieurs outils offre une couverture maximale. Les projets de reference (Google, Spring) combinent Error Prone + Checkstyle minimum.

**Actions :**
- [ ] **Error Prone** (compile-time, Google) — detecte 400+ patterns de bugs. Le plus efficace selon les etudes comparatives. Configurer comme plugin compilateur.
- [ ] **Checkstyle** — conventions de code (nommage, structure, Javadoc). Definir un `warp-checks.xml` inspire de Minestom et adapte au projet.
- [ ] **SpotBugs** + **find-sec-bugs** — bugs runtime + vulnerabilites securite. Plus utile pour le code reseau du proxy.
- [ ] Optionnel : **PMD** pour les regles de complexite (cognitive complexity, God classes)
- [ ] Faire echouer le build en CI pour toute violation (zero tolerance, pas de `@SuppressWarnings` sans justification en commentaire)

### 2.3 Conventions de code

**Actions :**
- [ ] Documenter les conventions dans `CONTRIBUTING.md` :
  - Palantir Java Format (120 colonnes)
  - Pas de `@author` dans les Javadoc (Git trace l'historique — cf. Quarkus)
  - Pas de wildcard imports
  - Noms de packages en minuscules, sans underscores
  - Constantes en `UPPER_SNAKE_CASE`
  - Methodes et variables en `camelCase`
  - Commit messages en Conventional Commits (`feat(protocol):`, `fix(proxy):`, etc.)

### 2.4 Immutabilite et Java moderne

Utiliser les fonctionnalites post-Java 17 pour un code plus sur et expressif.

**Actions :**
- [ ] Utiliser des **records** pour tous les DTOs, events, et valeurs immutables (55% d'adoption en 2024 — feature Java la plus populaire)
- [ ] Utiliser des **sealed classes/interfaces** pour les hierarchies de types fermes (types de paquets, etats de connexion, resultats d'evenements)
- [ ] Combiner sealed + records pour du pattern matching exhaustif :
  ```java
  sealed interface ConnectionResult permits Connected, Refused, TimedOut {}
  record Connected(ServerInfo server) implements ConnectionResult {}
  record Refused(Component reason) implements ConnectionResult {}
  record TimedOut(Duration elapsed) implements ConnectionResult {}
  ```
- [ ] Privilegier les collections immutables (`List.of()`, `Map.of()`, `Set.copyOf()`)
- [ ] Marquer les classes non extensibles comme `final`

---

## 3. Null safety

JSpecify est desormais LE standard industriel (adopte par Spring 7, IntelliJ 2025.3+, Google).

**Actions :**
- [ ] Ajouter la dependance `org.jspecify:jspecify:1.0.0`
- [ ] Annoter chaque `package-info.java` public avec `@NullMarked` (le defaut devient non-null)
- [ ] Utiliser `@Nullable` uniquement la ou null est un retour valide
- [ ] Integrer **NullAway** (Error Prone plugin) pour verification a la compilation :
  ```
  // Error Prone + NullAway config
  -Xep:NullAway:ERROR
  -XepOpt:NullAway:AnnotatedPackages=net.warp
  ```
- [ ] Le build doit **echouer** si une API publique viole les contraintes de nullite
- [ ] Documenter la strategie dans l'ADR-001 (Architecture Decision Record)

---

## 4. Design d'API

### 4.1 Principes fondamentaux (Joshua Bloch)

**Actions :**
- [ ] **Minimiser la surface publique** — chaque API ajoutee est un engagement permanent. En cas de doute, ne pas exposer.
- [ ] **Principe de moindre surprise** — chaque methode fait la chose la moins surprenante etant donne son nom
- [ ] **Fail fast** — valider les parametres tot, erreurs a la compilation plutot qu'au runtime
- [ ] **Ne pas forcer le client a faire ce que la lib peut faire** — pas de boilerplate cote utilisateur
- [ ] **Bon type pour le job** — pas de `String` la ou un enum, un record, ou un type dedie convient
- [ ] **Surcharges prudentes** — si deux methodes different en comportement, leur donner des noms differents

### 4.2 Annotations @API (approche JUnit 5 / API Guardian)

Indispensable pour un proxy qui sera consomme comme dependance.

**Actions :**
- [ ] Ajouter la dependance `org.apiguardian:apiguardian-api`
- [ ] Annoter toute API publique avec `@API(status)` :
  | Status | Signification | Garantie |
  |--------|--------------|----------|
  | `STABLE` | API figee | Pas de breaking change dans la version majeure |
  | `EXPERIMENTAL` | Nouvelle feature, feedback voulu | Peut changer ou disparaitre a tout moment |
  | `INTERNAL` | Usage interne uniquement | Aucune garantie, peut changer sans preavis |
  | `DEPRECATED` | Sera supprime | Migration guide obligatoire |
- [ ] Configurer un check CI qui empeche de modifier une API `STABLE` de maniere incompatible

### 4.3 Patterns d'API

**Actions :**
- [ ] **Builders** pour les objets complexes (config, server info, events) :
  ```java
  ServerInfo server = ServerInfo.builder()
      .name("lobby")
      .address("localhost", 25566)
      .build();
  ```
- [ ] **Fluent API** pour les operations chainees (event registration, filters)
- [ ] Retourner `this` pour les setters de builder, jamais `void`
- [ ] Pas de constructeurs publics avec plus de 3 parametres → builder obligatoire
- [ ] Utiliser des `Optional<T>` en retour (jamais en parametre)
- [ ] Interfaces au lieu de classes abstraites pour les points d'extension

### 4.4 Compatibilite ascendante et SemVer

**Actions :**
- [ ] Adherer strictement a SemVer 2.0.0 :
  - PATCH : corrections de bugs sans changement d'API
  - MINOR : ajouts d'API retrocompatibles
  - MAJOR : changements incompatibles
- [ ] Politique de deprecation :
  1. Annoter `@Deprecated(since = "X.Y", forRemoval = true)` + `@API(DEPRECATED)`
  2. Javadoc avec `@deprecated Utiliser {@link NouvelleMethode} a la place.`
  3. Maintenir la methode deprecee pendant au moins 1 version mineure
  4. Supprimer uniquement dans une version majeure
- [ ] Utiliser **japicmp** (Gradle plugin) pour detecter automatiquement les breaking changes en CI :
  ```kotlin
  // Compare le JAR actuel avec la derniere version publiee
  japicmp {
      oldArchive = "net.warp:warp-api:${lastReleasedVersion}"
      onlyModified = true
      failOnModification = true  // pour les branches non-major
  }
  ```

---

## 5. Documentation

### 5.1 Javadoc

**Actions :**
- [ ] **100% de couverture Javadoc sur l'API publique** (`warp-api`, `warp-plugin-api`). Configurer Checkstyle pour l'imposer.
- [ ] Chaque classe publique : description du role + `@since`
- [ ] Chaque methode publique : description + `@param` + `@return` + `@throws` + `@since`
- [ ] Ordre des tags : `@param`, `@return`, `@throws`, `@see`, `@since`, `@deprecated`
- [ ] Inclure des exemples de code dans les Javadoc des APIs principales :
  ```java
  /**
   * Registers a plugin-scoped event listener.
   *
   * <pre>{@code
   * eventBus.on(PlayerLoginEvent.class, event -> {
   *     event.player().sendMessage("Welcome!");
   * });
   * }</pre>
   *
   * @param eventType the event class to listen for
   * @param handler   the handler to invoke
   * @param <E>       the event type
   * @return a registration that can be used to unregister
   * @since 1.0
   */
  ```
- [ ] Pas de Javadoc triviale ("Gets the name" → plutot expliquer le contrat, les contraintes, le thread-safety)
- [ ] Publier la Javadoc automatiquement sur GitHub Pages a chaque release

### 5.2 Site de documentation (Docusaurus)

Choix recommande : **Docusaurus** (Meta). Raisons :
- React-based, composants interactifs possibles
- Versioning et i18n natifs
- Utilise par des projets de reference (Netty Reactor, nombreux projets CNCF)
- MkDocs Material est en mode maintenance depuis novembre 2025
- GitBook n'est plus open-source

**Actions :**
- [ ] Creer un site Docusaurus dans `docs-site/` :
  - **Getting Started** — 5 minutes pour un proxy fonctionnel
  - **Concepts** — Architecture, blind forwarding, event system, drain system
  - **Guides** — Plugin development, deployment K8s, migration depuis Velocity
  - **API Reference** — Liens vers la Javadoc
  - **Changelog** — Genere automatiquement
- [ ] Deployer sur GitHub Pages via GitHub Actions
- [ ] Ajouter un bouton "Edit this page" (lien vers le fichier source)

### 5.3 Architecture Decision Records (ADRs)

Les ADRs documentent les decisions techniques majeures et leur raisonnement. Indispensable pour la credibilite technique.

**Actions :**
- [ ] Creer `docs/adr/` avec le format de Michael Nygard :
  ```
  # ADR-NNN: Titre de la decision

  ## Statut
  Accepte | Propose | Remplace par ADR-XXX

  ## Contexte
  Quel probleme resolvons-nous ?

  ## Decision
  Qu'avons-nous decide ?

  ## Consequences
  Quels sont les effets positifs et negatifs ?
  ```
- [ ] ADRs prioritaires pour Warp :
  - ADR-001 : Strategie null safety (JSpecify + NullAway)
  - ADR-002 : Blind forwarding (pourquoi, comment, limites)
  - ADR-003 : Systeme d'evenements (design, thread model, garanties d'ordre)
  - ADR-004 : Drain system et graceful shutdown
  - ADR-005 : Choix de licence (Apache 2.0)
  - ADR-006 : Strategie de versioning (SemVer + @API Guardian)
  - ADR-007 : Modele de threading (event loops, worker pools)
  - ADR-008 : Systeme de plugins (SPI, lifecycle, isolation)

### 5.4 README.md

**Actions :**
- [ ] Structure :
  1. Logo / banniere
  2. Phrase d'accroche (1 ligne : ce que fait Warp et pourquoi il est different)
  3. Badges (CI, version Maven Central, licence, Discord, couverture)
  4. Features cles (5-7 bullet points)
  5. Quickstart (copier-coller en 30 secondes)
  6. Comparaison Velocity/BungeeCord (tableau factuel, pas marketing)
  7. Liens : docs, Javadoc, Discord, Contributing
  8. Licence

---

## 6. CI/CD

### 6.1 Pipeline GitHub Actions

**Actions :**
- [ ] **Workflow `ci.yml`** (sur chaque push et PR) :
  ```yaml
  jobs:
    build:
      strategy:
        matrix:
          java: [21, 22, 23]          # LTS + recentes
          os: [ubuntu-latest, windows-latest]  # Multi-OS
      steps:
        - uses: actions/checkout@v4
        - uses: actions/setup-java@v4
          with:
            java-version: ${{ matrix.java }}
            distribution: temurin
            cache: gradle
        - run: ./gradlew spotlessCheck
        - run: ./gradlew build
        - run: ./gradlew jacocoTestReport
        - uses: codecov/codecov-action@v4
  ```
- [ ] **Workflow `benchmarks.yml`** (sur push main + PRs avec label `perf`) :
  - Executer les JMH benchmarks
  - Comparer avec la baseline via `benchmark-action/github-action-benchmark`
  - Commenter la PR si regression > seuil configurable
- [ ] **Workflow `release.yml`** (sur tag `v*`) :
  - Build + tests
  - Publier sur Maven Central
  - Generer les release notes
  - Publier la Javadoc sur GitHub Pages
  - Deployer la documentation Docusaurus

### 6.2 Checks de qualite en CI

**Actions :**
- [ ] `spotlessCheck` — formatage
- [ ] `checkstyleMain` — conventions
- [ ] Error Prone + NullAway — bugs + null safety
- [ ] SpotBugs + find-sec-bugs — bugs runtime + securite
- [ ] `japicmp` — compatibilite ascendante (sur les branches non-major)
- [ ] `jacocoTestReport` — couverture de code
- [ ] Build sur Java 21 (LTS) + Java latest (compatibilite future)
- [ ] Build sur Linux + Windows (portabilite)

### 6.3 Gestion des dependances

**Actions :**
- [ ] Configurer **Renovate** (prefere a Dependabot) :
  - Support superieur de Gradle et des version catalogs
  - Grouping des monorepo packages en une seule PR
  - Automerge sur les patches apres tests verts
  - Config partageable via preset
  - Multi-plateforme (si migration GitLab un jour)
- [ ] Fichier `renovate.json` :
  ```json
  {
    "$schema": "https://docs.renovatebot.com/renovate-schema.json",
    "extends": [
      "config:recommended",
      "group:allNonMajor",
      ":automergeMinor",
      ":automergePatch"
    ],
    "labels": ["dependencies"],
    "packageRules": [
      {
        "matchUpdateTypes": ["major"],
        "automerge": false
      }
    ]
  }
  ```

---

## 7. Tests et benchmarks

### 7.1 Strategie de tests

**Actions :**
- [ ] **Tests unitaires** — JUnit 5 + Mockito. Objectif : couverture 80%+ sur la logique critique (protocole, event bus, routing)
- [ ] **Tests d'integration** — Module `warp-testing` fournissant un proxy embarque pour tester les plugins
- [ ] **Tests de concurrence** — jcstress (cf. Minestom) pour les structures de donnees partagees (event bus, connection registry)
- [ ] **Tests de resilience** — Simuler des deconnexions, timeouts, paquets malformes
- [ ] **Property-based tests** — JQwik pour les codecs de paquets (encode → decode = identite)
- [ ] Nommer les tests avec un pattern clair : `should_[comportement]_when_[condition]()`

### 7.2 Benchmarks JMH

**Actions :**
- [ ] Module `warp-benchmarks/` avec des benchmarks pour :
  - Throughput de blind forwarding (paquets/seconde)
  - Latence de routing
  - Compression/decompression
  - Event dispatch
  - Connection handshake
- [ ] Tracker les resultats dans le temps via `benchmark-action/github-action-benchmark`
- [ ] Publier les graphiques de performance sur GitHub Pages
- [ ] Comparer avec Velocity sur les memes metriques (argument marketing factuel)

---

## 8. Gestion des releases

### 8.1 Versioning semantique automatise

**Actions :**
- [ ] Utiliser **Conventional Commits** pour les messages de commit :
  - `feat(protocol):` → bump MINOR
  - `fix(proxy):` → bump PATCH
  - `feat!:` ou `BREAKING CHANGE:` → bump MAJOR
- [ ] Integrer **semantic-release** ou **JReleaser** pour :
  - Calcul automatique de la prochaine version
  - Generation du CHANGELOG.md
  - Creation du tag Git
  - Creation de la GitHub Release avec notes
- [ ] Conserver la version dans `gradle.properties` (source de verite)

### 8.2 Publication Maven Central

Depuis juin 2025, OSSRH est obsolete. Il faut utiliser la nouvelle API Central Portal.

**Actions :**
- [ ] Utiliser le plugin `com.vanniktech.maven.publish` (le plus maintenu pour Gradle)
- [ ] Configurer la signature GPG dans les secrets GitHub Actions
- [ ] Workflow automatise :
  1. Tag `v1.2.3` pousse
  2. Build + tests complets
  3. `publishAndReleaseToMavenCentral`
  4. Javadoc deployee
  5. CHANGELOG mis a jour
- [ ] Publier des snapshots sur chaque merge dans `main` (Sonatype snapshots)
- [ ] Publier les artefacts pour les PRs avec label `publish` (comme Minestom)

### 8.3 Changelog

**Actions :**
- [ ] Generer automatiquement a partir des Conventional Commits
- [ ] Format : regrouper par `feat`, `fix`, `perf`, `breaking`
- [ ] Inclure les liens vers les PRs et les contributeurs

---

## 9. Communaute

### 9.1 Templates GitHub

**Actions :**
- [ ] `.github/ISSUE_TEMPLATE/bug_report.yml` :
  - Version Warp, version Java, OS
  - Etapes de reproduction
  - Comportement attendu vs reel
  - Logs/stacktrace
- [ ] `.github/ISSUE_TEMPLATE/feature_request.yml` :
  - Description du besoin
  - Cas d'usage
  - Alternatives considerees
- [ ] `.github/ISSUE_TEMPLATE/config.yml` :
  - Lien vers Discord pour les questions de support
- [ ] `.github/PULL_REQUEST_TEMPLATE.md` :
  - Description des changements
  - Issue liee
  - Checklist : tests, Javadoc, formatting, changelog entry
  - Type de changement (bug fix, feature, breaking change)

### 9.2 CONTRIBUTING.md

Aller au-dela du minimum (cf. Quarkus). Un bon guide de contribution reduit la charge de review.

**Actions :**
- [ ] Sections :
  1. **Comment contribuer** — fork, branch, PR, review
  2. **Setup du dev environment** — prerequisites, IDE config (IntelliJ recommande), build commands
  3. **Architecture rapide** — schema des modules, ou aller pour quel type de changement
  4. **Conventions de code** — formatage, nommage, Javadoc, null safety
  5. **Tests** — comment ecrire et executer les tests
  6. **Commit messages** — Conventional Commits obligatoires
  7. **Review process** — delais attendus, qui review, quand merger

### 9.3 Labels et "Good First Issues"

Les etudes montrent que taguer ~25% des issues en "Good First Issue" augmente de 13% le nombre de nouveaux contributeurs.

**Actions :**
- [ ] Labels standards :
  - `good first issue` — issues accessibles, bien documentees
  - `help wanted` — issues ou l'aide communautaire est bienvenue
  - `bug`, `enhancement`, `documentation`, `performance`
  - `priority:critical`, `priority:high`, `priority:medium`, `priority:low`
  - `area:protocol`, `area:proxy`, `area:api`, `area:plugin`, `area:ci`
  - `status:needs-triage`, `status:confirmed`, `status:wontfix`
  - `breaking-change`
- [ ] Pour chaque "Good First Issue", inclure :
  - Description claire du probleme
  - Fichier(s) concerne(s)
  - Approche suggeree
  - Lien vers la doc pertinente

### 9.4 Serveur Discord

**Actions :**
- [ ] Canaux essentiels :
  - `#announcements` — releases, breaking changes
  - `#general` — discussions libres
  - `#help` — support utilisateurs
  - `#development` — discussions techniques entre contributeurs
  - `#showcase` — projets utilisant Warp
  - `#rfc` — discussions sur les RFCs/propositions majeures
- [ ] Onboarding : bot d'accueil avec roles (utilisateur, contributeur, plugin-dev)
- [ ] Forum channels pour le support (persistance des solutions, supplement a la doc officielle)
- [ ] Recruter 3-5 membres actifs comme moderateurs tot dans le projet

### 9.5 Processus RFC

Pour les changements majeurs qui impactent l'API publique ou l'architecture.

**Actions :**
- [ ] Creer `.github/ISSUE_TEMPLATE/rfc.yml` :
  ```
  ## RFC-NNN : Titre

  ### Resume
  Description courte (~200 mots)

  ### Motivation
  Pourquoi ce changement est necessaire

  ### Design propose
  Description detaillee, diagrammes si pertinent

  ### Alternatives considerees
  Autres approches et pourquoi elles ont ete rejetees

  ### Compatibilite ascendante
  Impact sur les utilisateurs existants, plan de migration

  ### Plan d'implementation
  Etapes, timeline estimee
  ```
- [ ] Processus :
  1. Issue ouverte avec le template RFC
  2. Discussion communautaire pendant 2 semaines minimum
  3. Vote / decision des maintainers
  4. Implementation via PR(s) referencant le RFC

### 9.6 Roadmap transparente

**Actions :**
- [ ] Utiliser **GitHub Projects** (Board) pour la roadmap publique
- [ ] Colonnes : Backlog → Planned → In Progress → Review → Done
- [ ] Milestone par version (`v0.1.0`, `v0.2.0`, `v1.0.0`)
- [ ] Mettre a jour regulierement et communiquer les changements sur Discord

---

## 10. Licence et legal

### 10.1 Choix de licence : Apache 2.0

**Justification :**
- C'est la licence de Minestom, Netty, Quarkus, Vert.x — standard de l'ecosysteme Java
- Protection explicite contre les brevets (contrairement a MIT)
- Clause de terminaison defensive (si un utilisateur attaque, sa licence est revoquee)
- Compatible avec la quasi-totalite des licences (sauf GPL v2)
- Preferee par les entreprises pour les deployments en production
- Permet l'utilisation dans des projets proprietaires (important pour les serveurs MC commerciaux)

**Actions :**
- [ ] Fichier `LICENSE` avec le texte complet Apache 2.0
- [ ] Header de licence dans chaque fichier source :
  ```java
  /*
   * Copyright (C) 2026 Warp Contributors
   *
   * Licensed under the Apache License, Version 2.0 (the "License");
   * you may not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *     http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS,
   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   * See the License for the specific language governing permissions and
   * limitations under the License.
   */
  ```
- [ ] Configurer Spotless pour verifier/appliquer le header automatiquement
- [ ] Fichier `NOTICE` listant les attributions tierces

### 10.2 CLA : Pas recommande

**Justification :**
- Un CLA cree une barriere bureaucratique qui decourage la participation
- Asymetrie de pouvoir legal (MongoDB et Elasticsearch ont utilise leur CLA pour passer en licence non-open-source)
- Charge administrative de tracking
- Non necessaire si la licence du projet est claire (Apache 2.0 suffit)
- L'alternative legere : **DCO (Developer Certificate of Origin)** via `Signed-off-by:` dans les commits

**Actions :**
- [ ] Utiliser le **DCO** (un simple `git commit -s`) au lieu d'un CLA
- [ ] Integrer le bot GitHub DCO qui verifie que chaque commit est signe
- [ ] Documenter dans CONTRIBUTING.md

---

## Priorites d'implementation

### Phase 1 — Fondations (avant le premier commit public)
1. Structure multi-module Gradle + convention plugins + version catalog
2. Spotless + Palantir Java Format
3. Error Prone + NullAway + JSpecify
4. `.editorconfig`, `.gitignore`, `.gitattributes`
5. Licence Apache 2.0 + header automatique
6. CI de base (build + format check + static analysis)
7. README.md minimal

### Phase 2 — Credibilite technique (avant l'annonce publique)
1. Javadoc complete sur `warp-api`
2. @API Guardian annotations sur toute l'API publique
3. Tests unitaires 80%+ sur le coeur
4. ADRs pour les decisions architecturales cles
5. CONTRIBUTING.md complet
6. Templates GitHub (issues, PRs)
7. Benchmarks JMH de base
8. japicmp pour la compatibilite ascendante

### Phase 3 — Communaute (a l'annonce publique)
1. Site Docusaurus avec Getting Started + Concepts
2. Serveur Discord structure
3. Roadmap publique sur GitHub Projects
4. Labels et "Good First Issues" prepares
5. Publication Maven Central automatisee
6. Changelog automatise

### Phase 4 — Maturite (post-v1.0)
1. Processus RFC formalise
2. Benchmarks continus avec tracking de regression
3. Renovate pour les dependances
4. Documentation complete (guides, migration, deployment)
5. Plugin marketplace / registry

---

## Projets de reference a etudier en detail

| Projet | A etudier | Lien |
|--------|-----------|------|
| **Netty** | Structure modulaire, NOTICE file, longevite, adoption industrielle | github.com/netty/netty |
| **Minestom** | Meme domaine, Gradle Kotlin DSL, jcstress, JMH, code generators | github.com/Minestom/Minestom |
| **JUnit 5** | @API Guardian, evolution d'API, documentation exemplaire | github.com/junit-team/junit5 |
| **Vert.x** | Event loop model, multi-module massif, documentation | github.com/eclipse-vertx/vert.x |
| **Quarkus** | CONTRIBUTING.md, CI/CD, review process, extension model | github.com/quarkusio/quarkus |
| **Helidon** | API moderne, Java 21+, virtual threads | github.com/helidon-io/helidon |

---

## Sources

- [Top Java Open Source Projects](https://www.upgrad.com/blog/java-open-source-projects/)
- [Java Static Analysis Tools Comparison](https://www.tatvasoft.com/outsourcing/2024/09/java-static-code-analysis-tools.html)
- [Static Analysis for Java - Symflower](https://symflower.com/en/company/blog/2024/static-analysis-java/)
- [JSpecify Null Safety - Baeldung](https://www.baeldung.com/java-jspecify-null-safety)
- [Spring Null Safety with JSpecify](https://spring.io/blog/2025/03/10/null-safety-in-spring-apps-with-jspecify-and-null-away/)
- [JSpecify User Guide](https://jspecify.dev/docs/user-guide/)
- [JUnit 5 API Evolution](https://docs.junit.org/6.0.3/api-evolution.html)
- [ADR GitHub Pages](https://adr.github.io/)
- [Docusaurus vs MkDocs vs GitBook](https://unmarkdown.com/blog/gitbook-vs-docusaurus-vs-mkdocs)
- [MkDocs Material Alternatives](https://squidfunk.github.io/mkdocs-material/alternatives/)
- [Joshua Bloch - API Design](https://www.infoq.com/articles/API-Design-Joshua-Bloch/)
- [Java API Best Practices - DZone](https://dzone.com/refcardz/java-api-best-practices)
- [SemVer for Java Libraries](https://foojay.io/today/semantic-versioning-your-java-libraries/)
- [SemVer 2.0.0](https://semver.org/)
- [GitHub Actions CI/CD Best Practices](https://github.com/github/awesome-copilot/blob/main/instructions/github-actions-ci-cd-best-practices.instructions.md)
- [JMH Benchmark Action](https://github.com/kitlangton/jmh-benchmark-action)
- [Continuous Benchmark Action](https://github.com/benchmark-action/github-action-benchmark)
- [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)
- [Gradle Convention Plugins](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html)
- [Gradle Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html)
- [Gradle Build Cache](https://docs.gradle.org/current/userguide/build_cache.html)
- [Maven Central Publishing with Gradle (2025)](https://blog.jora.dev/en/posts/publish-to-maven-central-with-gradle-in-2025/)
- [Spotless Plugin](https://github.com/diffplug/spotless)
- [Palantir Java Format](https://github.com/palantir/palantir-java-format)
- [Renovate vs Dependabot](https://www.turbostarter.dev/blog/renovate-vs-dependabot-whats-the-best-tool-to-automate-your-dependency-updates)
- [Renovate Bot Comparison](https://docs.renovatebot.com/bot-comparison/)
- [Open Source Community Building 2025](https://dev.to/axrisi/growing-your-open-source-community-in-2025-strategies-for-sustainable-projects-2lln)
- [Good First Issues Strategy](https://daily.dev/blog/open-source-contributor-onboarding-10-tips)
- [Discord for Open Source](https://glasskube.dev/blog/discord-setup/)
- [RFC Process - Apache Hudi](https://cwiki.apache.org/confluence/display/HUDI/RFC+Process)
- [Open Source Licenses 2024](https://opensource.org/blog/top-open-source-licenses-in-2024)
- [MIT vs Apache 2.0](https://licensecheck.io/blog/mit-apache-comparison)
- [CLA Problems](https://opensource.com/article/19/2/cla-problems)
- [Apache NOTICE Policy](https://www.apache.org/legal/src-headers.html)
- [Netty Architecture](https://deepwiki.com/netty/netty)
- [Minestom GitHub](https://github.com/Minestom/Minestom)
- [Quarkus CONTRIBUTING.md](https://github.com/quarkusio/quarkus/blob/main/CONTRIBUTING.md)
- [Java Records Guide](https://dev.to/aaravjoshi/java-records-ultimate-guide-how-to-write-clean-immutable-data-classes-2024-1ped)
- [Sealed Classes in Java](https://www.javacodegeeks.com/2024/11/javas-modern-toolbox-records-sealed-classes-and-pattern-matching.html)
- [Velocity Minecraft Proxy](https://papermc.io/software/velocity/)
