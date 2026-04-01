# Architecture DX du systeme de plugins Warp

**Date** : 31 mars 2026
**Objectif** : Concevoir le MEILLEUR systeme de plugins de l'ecosysteme MC proxy -- superieur a Velocity, inspire des meilleurs systemes d'extension de l'industrie.
**Sources** : PF4J, Caddy modules, IntelliJ Platform, Gradle plugins, VS Code extensions, WordPress, Velocity/BungeeCord, Cloud/Lamp/Brigadier, Configurate, Avaje Inject, MockBukkit, Modrinth/Hangar, OSGi, PaperMC.

---

## TABLE DES MATIERES

1. [Analyse comparative des systemes d'extension](#1-analyse-comparative)
2. [Architecture plugin recommandee](#2-architecture-plugin)
3. [Injection de dependances](#3-injection-de-dependances)
4. [Systeme d'evenements](#4-systeme-devenements)
5. [Framework de commandes](#5-framework-de-commandes)
6. [Configuration DX](#6-configuration-dx)
7. [Isolation et securite](#7-isolation-et-securite)
8. [SDK et outillage](#8-sdk-et-outillage)
9. [Testing framework](#9-testing-framework)
10. [Distribution et marketplace](#10-distribution-et-marketplace)
11. [Qualite des messages d'erreur](#11-qualite-des-erreurs)
12. [Roadmap d'implementation](#12-roadmap)

---

## 1. ANALYSE COMPARATIVE DES SYSTEMES D'EXTENSION {#1-analyse-comparative}

### 1.1 Matrice comparative

| Systeme | Force principale | Faiblesse principale | Lecon pour Warp |
|---------|-----------------|---------------------|-----------------|
| **Caddy** (Go) | Lifecycle propre (Provision/Validate/Cleanup), hot-swap atomique, namespaces | Compilation statique (pas de chargement dynamique) | Lifecycle rigoureux, config atomique |
| **IntelliJ** (Java) | 1700+ extension points, services a la demande, plugins dynamiques | Complexite, courbe d'apprentissage | Extension points bien definis, services lazy |
| **Gradle** (Java) | Convention over configuration, plugins composables, type-safe | DSL complexe, documentation diffuse | Conventions sensees, composabilite |
| **VS Code** (JS) | Activation lazy, contribution points declaratifs, marketplace | Pas d'isolation forte entre extensions | Activation paresseuse, manifeste declaratif |
| **WordPress** (PHP) | Enorme ecosysteme, hooks simples | Collisions de noms, pas d'isolation, spaghetti | Namespaces obligatoires, isolation |
| **PF4J** (Java) | Leger (~100KB), extension points, classloader par plugin | Pas de DI integre, API minimale | Base solide pour l'isolation |
| **Velocity** (Java) | Event system async avec Continuation, Guice DI | Constructeur chicken-egg, pas de config API, lourd | Corriger tous les defauts |
| **BungeeCord** (Java) | API simple, ecosysteme massif | Events brises, pas d'isolation, securite nulle | Simplicite de l'API de surface |

### 1.2 Les 7 principes fondateurs (synthetises de l'analyse)

Chaque systeme d'extension reussi partage ces traits :

1. **Lifecycle explicite** (Caddy) -- Les plugins savent exactement quand ils sont crees, configures, valides, demarres, arretes, nettoyes
2. **Activation paresseuse** (VS Code) -- Ne charger que ce qui est necessaire, quand c'est necessaire
3. **Convention over configuration** (Gradle) -- Les defauts intelligents reduisent le boilerplate a zero
4. **Extension points bien definis** (IntelliJ) -- Les points d'extension sont des contrats explicites, pas des hooks implicites
5. **Isolation par defaut** (PF4J/Paper) -- Chaque plugin dans son propre classloader, dependances isolees
6. **Outillage de premier ordre** (IntelliJ, VS Code) -- Templates, annotation processors, IDE support, testing framework
7. **Messages d'erreur pedagogiques** (Rust/Elm) -- Les erreurs guident le developpeur vers la solution

---

## 2. ARCHITECTURE PLUGIN RECOMMANDEE {#2-architecture-plugin}

### 2.1 Structure d'un plugin Warp

```
my-plugin/
  src/main/java/
    com/example/myplugin/
      MyPlugin.java          # Point d'entree annote @WarpPlugin
      MyConfig.java           # Record de configuration
      commands/
        MyCommand.java        # Commandes annotees
      listeners/
        MyListener.java       # Event listeners
  src/test/java/
    com/example/myplugin/
      MyPluginTest.java       # Tests avec MockWarp
  build.gradle.kts            # Utilise le Gradle plugin Warp
```

### 2.2 Declaration de plugin -- ZERO boilerplate

**Recommandation definitive** : Annotation + Annotation Processor (genere les metadonnees automatiquement).

Inspiration : Sponge `@Plugin`, Lamp `@Command`, Spigot plugin-annotations.

```java
@WarpPlugin(
    id = "my-plugin",
    name = "My Plugin",
    version = "1.0.0",
    authors = {"MonPseudo"},
    description = "Un super plugin",
    dependencies = {
        @Dependency(id = "luckperms", optional = true),
        @Dependency(id = "warp-api", version = ">=1.0.0")
    }
)
public final class MyPlugin {

    // Injection automatique -- PAS de constructeur a probleme comme Velocity
    @Inject private EventManager events;
    @Inject private CommandManager commands;
    @Inject private Logger logger;
    @Inject private PluginDataDirectory dataDir;
    @Inject private MyConfig config;  // Config type-safe injectee directement

    @OnEnable
    public void onEnable() {
        logger.info("Plugin actif !");
        events.register(new MyListener());
        commands.register(new MyCommand());
    }

    @OnDisable
    public void onDisable() {
        // Nettoyage automatique des listeners/commandes si non fait
    }
}
```

**Pourquoi c'est mieux que Velocity** :
- Velocity force un constructeur avec `@Inject` qui cree un probleme "chicken-egg" (le plugin n'est pas encore enregistre quand le constructeur s'execute, donc impossible d'enregistrer des listeners)
- Velocity n'a PAS de config API -- les devs doivent tout coder a la main
- Velocity utilise Guice qui est lourd et runtime-based

### 2.3 Lifecycle du plugin

Inspire directement de Caddy mais adapte a Java/MC :

```
DISCOVERED -> LOADED -> ENABLED -> DISABLED -> UNLOADED
     |           |          |          |           |
  scan JAR   classload   @OnEnable  @OnDisable   cleanup
  + valider   + DI        + start    + stop       GC classloader
  metadata    inject      services   services
              deps
```

**Phases detaillees** :

| Phase | Description | Equivalent Caddy | Garanties |
|-------|-------------|-----------------|-----------|
| `DISCOVERED` | JAR scanne, metadonnees lues et validees | Registration | Aucun code plugin execute |
| `LOADED` | ClassLoader cree, DI resolu, config chargee | Provision | Toutes les dependances disponibles |
| `ENABLED` | `@OnEnable` appele, plugin operationnel | Start | Event bus et commandes actifs |
| `DISABLED` | `@OnDisable` appele, nettoyage | Stop | Plus aucun event dispatche |
| `UNLOADED` | ClassLoader ferme, GC | Cleanup | Toutes les refs supprimees |

**Garantie critique** : Comme Caddy, pendant un reload, le nouveau plugin est `LOADED` et valide AVANT que l'ancien soit `DISABLED`. Overlap controle pour zero downtime.

### 2.4 Descripteur genere automatiquement

L'annotation processor genere un `warp-plugin.json` dans `META-INF/` :

```json
{
  "id": "my-plugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "api-version": "1.0.0",
  "main": "com.example.myplugin.MyPlugin",
  "authors": ["MonPseudo"],
  "dependencies": [
    { "id": "luckperms", "optional": true },
    { "id": "warp-api", "version": ">=1.0.0" }
  ],
  "extensions": [
    "com.example.myplugin.listeners.MyListener",
    "com.example.myplugin.commands.MyCommand"
  ]
}
```

Le developpeur n'ecrit JAMAIS ce fichier a la main. L'annotation processor le genere et valide a la compilation.

---

## 3. INJECTION DE DEPENDANCES {#3-injection-de-dependances}

### 3.1 Comparaison des approches DI

| Approche | Avantages | Inconvenients | Verdict |
|----------|-----------|---------------|---------|
| **Guice** (Velocity) | Mature, flexible, bindings runtime | Lourd (~900KB), reflexion runtime, erreurs cryptiques, lent au demarrage | REJETE |
| **Dagger 2** | Compile-time, rapide, leger | API rigide, composants difficiles a etendre pour un systeme de plugins | REJETE |
| **Spring** | Tres riche | Enorme, overkill pour un proxy | REJETE |
| **Avaje Inject** | Compile-time, JSR-330, leger (~200KB), source lisible, ServiceLoader natif | Plus jeune, moins connu | CANDIDAT |
| **DI maison + ServiceLoader** | Controle total, zero dep, adapte exactement | Plus de code a maintenir | CANDIDAT |
| **Hybride : DI maison + Avaje optionnel** | Le meilleur des deux mondes | Complexite marginale | **RECOMMANDE** |

### 3.2 Recommandation definitive : DI maison legere + Avaje optionnel

**Architecture en couches** :

1. **Couche core (DI maison)** : Le proxy Warp fournit un mini-conteneur DI qui :
   - Resout `@Inject` sur les champs du plugin principal (comme ci-dessus)
   - Fournit les services core : `EventManager`, `CommandManager`, `ProxyServer`, `Logger`, `PluginDataDirectory`, `Config<T>`
   - Utilise `ServiceLoader` (Java SPI) pour decouvrir les plugins
   - Zero reflexion pour les services core (genere par annotation processor)

2. **Couche optionnelle (Avaje Inject)** : Les plugins COMPLEXES peuvent ajouter Avaje en dependance pour :
   - DI compile-time dans leurs propres classes internes
   - Scopes, `@Factory`, `@Bean`, conditions
   - Integration transparente avec le conteneur core via `InjectPlugin` SPI

**Pourquoi pas Guice comme Velocity** :
- Guice utilise de la reflexion runtime = erreurs tardives, stacktraces incomprehensibles
- Guice ajoute ~900KB de deps
- Les erreurs Guice sont HORRIBLES pour les devs plugins ("No implementation bound for X", sans dire OUP ni POURQUOI)
- Guice ne peut pas valider le graphe de dependances a la compilation

**Pourquoi le DI maison est suffisant pour 90% des plugins** :
- Un plugin proxy a besoin de 5 a 10 services injectes (events, commands, config, logger, server, scheduler)
- Ce sont des singletons connus a l'avance
- Pas besoin de scopes, factories, ou aspect-oriented programming
- L'annotation processor genere le code de wiring : zero reflexion, zero magie

### 3.3 Services inter-plugins

```java
// Plugin A exporte un service
@WarpPlugin(id = "economy")
public class EconomyPlugin {

    @Provides  // Ce service est disponible pour les autres plugins
    public EconomyService economyService() {
        return new VaultEconomyService();
    }
}

// Plugin B consomme le service
@WarpPlugin(id = "shop", dependencies = @Dependency(id = "economy"))
public class ShopPlugin {

    @Inject private EconomyService economy;  // Resolu automatiquement

    @OnEnable
    public void onEnable() {
        economy.getBalance(player);
    }
}
```

**Resolution** : Le conteneur DI de Warp maintient un registre global de services exportes (`@Provides`). Quand un plugin demande `@Inject EconomyService`, le conteneur cherche d'abord dans le plugin local, puis dans le registre global. Erreur claire si absent et non-optionnel.

---

## 4. SYSTEME D'EVENEMENTS {#4-systeme-devenements}

### 4.1 Problemes identifies dans les proxys existants

| Probleme | Velocity | BungeeCord | Impact |
|----------|----------|------------|--------|
| Race condition reconnexion rapide | OUI (#1013) | OUI (#2815) | LuckPerms, BungeeTabList casses |
| Event fire en mauvais etat | NON | OUI (#3519) | Kick immediat au server switch |
| Intent async jamais resolue | NON | OUI (#1858) | Event bus bloque |
| Constructeur chicken-egg | OUI | NON | Impossible de register dans le ctor |
| DisconnectEvent apres cleanup | OUI (#289, #1691) | OUI | Donnees corrompues |

### 4.2 Architecture recommandee

**Inspiration** : Velocity Continuation (bonne idee) + Caddy lifecycle (garanties) + corrige les defauts.

```java
// Listener simple -- syntaxe propre
public class MyListener {

    @Subscribe
    public void onLogin(PlayerLoginEvent event) {
        event.player().sendMessage("Bienvenue !");
    }

    // Event async avec CompletableFuture
    @Subscribe
    public CompletableFuture<Void> onPreLogin(PreLoginEvent event) {
        return database.checkBan(event.username())
            .thenAccept(banned -> {
                if (banned) event.deny("Tu es banni.");
            });
    }

    // Event async avec Continuation (style Velocity, mais ameliore)
    @Subscribe
    public EventTask onServerSwitch(ServerPreConnectEvent event) {
        return EventTask.withContinuation(continuation -> {
            loadPlayerData(event.player()).thenRun(continuation::resume);
        });
    }
}
```

### 4.3 Garanties du systeme d'evenements Warp

| Garantie | Description | Ce que Velocity ne fait PAS |
|----------|-------------|----------------------------|
| **Ordre strict par UUID** | `Disconnect(ancien)` complete AVANT `Login(nouveau)` pour le meme joueur | Velocity ne garantit pas ca (#1013) |
| **Phase-aware** | Les events post-switch ne fire qu'apres la phase PLAY atteinte | BungeeCord fire en CONFIG (#3519) |
| **Timeout automatique** | Les continuations ont un timeout configurable (defaut: 5s) | BungeeCord intent peut bloquer indefiniment (#1858) |
| **Thread-safe par design** | Chaque listener recoit une copie immutable ou un objet thread-safe | Races dans les deux proxys |
| **Pas de chicken-egg** | `@Subscribe` est scanne APRES l'injection, le plugin est enregistre | Velocity force `@Inject` dans le constructeur |
| **Erreurs claires** | Si un listener lance une exception, log contextuel + stack + quel plugin | Velocity : stack generique |

### 4.4 Events granulaires

Plutot que les events monolithiques de Velocity/BungeeCord, Warp utilise des events granulaires par phase :

```
Connexion du joueur :
  PreHandshakeEvent       -> peut refuser (anti-bot)
  PreLoginEvent           -> peut modifier username, refuser
  LoginEvent              -> authentication terminee
  PreConnectEvent         -> choix du serveur initial
  PostConnectEvent        -> joueur dans le serveur, phase PLAY active

Changement de serveur :
  ServerPreConnectEvent   -> peut modifier la destination
  ServerConnectingEvent   -> connexion en cours au backend
  ServerConnectedEvent    -> connexion backend etablie, pas encore en PLAY
  ServerPostConnectEvent  -> phase PLAY active, safe d'envoyer des paquets

Deconnexion :
  PreDisconnectEvent      -> le joueur va etre deconnecte (peut annuler si kick)
  DisconnectEvent         -> deconnexion effective, cleanup

Proxy lifecycle :
  ProxyStartEvent         -> proxy demarre, avant les plugins
  ProxyReadyEvent         -> tous les plugins charges
  ProxyShutdownEvent      -> arret initie
  ProxyDrainEvent         -> mode drain active
```

---

## 5. FRAMEWORK DE COMMANDES {#5-framework-de-commandes}

### 5.1 Comparaison des frameworks

| Framework | Approche | Avantages | Inconvenients | Verdict |
|-----------|----------|-----------|---------------|---------|
| **Brigadier** (Mojang) | Builder programmatique | Standard vanilla, suggestions natives | Tres verbeux, conflits d'arguments, pas de DI | REJETE comme API primaire |
| **Cloud** (Incendo) | Builder + Annotations + Kotlin DSL | Tres flexible, multi-plateforme, type-safe, futures | Courbe d'apprentissage, API complexe | INTEGRE en interne |
| **Lamp** (Revxrsal) | Annotations-first | DX excellente, multi-plateforme, DI integre, simple | Moins flexible pour les cas edge | INSPIRE l'API de surface |
| **ACF** (Aikar) | Annotations | Historique, populaire | Vieillissant, API datee | IGNORE |

### 5.2 Recommandation definitive : API annotation-first, Cloud en moteur interne

**Philosophie** : La MAJORITE des commandes de plugins proxy sont simples. L'API doit etre simple pour les cas simples, puissante pour les cas complexes.

```java
// CAS SIMPLE -- 90% des commandes de plugins proxy
@Command("lobby")
@Permission("myplugin.lobby")
@Description("Teleporte au lobby")
public class LobbyCommand {

    @Inject private ProxyServer server;

    @Default  // /lobby
    public void lobby(Player player) {
        server.connect(player, "lobby");
    }

    @Subcommand("list")  // /lobby list
    public void list(Player sender) {
        var lobby = server.getServer("lobby");
        sender.sendMessage("Joueurs au lobby: " + lobby.playerCount());
    }
}

// CAS AVANCE -- completions custom, arguments complexes
@Command("send")
@Permission("admin.send")
public class SendCommand {

    @Default
    public void send(
        @Argument("player") Player target,       // Auto-complete avec les joueurs en ligne
        @Argument("server") RegisteredServer dest // Auto-complete avec les serveurs
    ) {
        target.connect(dest);
        target.sendMessage("Transfere vers " + dest.name());
    }
}
```

**Ce que Warp fournit en plus** :
- **Auto-completion contextuelle** : Les arguments `Player`, `RegisteredServer`, etc. ont des completions built-in
- **Parsers extensibles** : Les plugins peuvent enregistrer des types custom avec `@Parser`
- **Erreurs utilisateur propres** : "Joueur 'xyz' introuvable" au lieu de stacktraces
- **Brigadier bridge** : Les commandes Warp s'enregistrent automatiquement dans Brigadier pour le tab-complete cote client
- **Cooldowns, confirmation, pagination** : Utilitaires built-in inspires de Lamp

### 5.3 Pour les cas tres avances : acces au builder Cloud

```java
// Pour les 10% de cas qui ont besoin de plus de controle
commandManager.buildCommand("complex")
    .permission("admin.complex")
    .argument(StringArgument.of("mode"))
    .argument(IntegerArgument.of("count", 1, 100))
    .handler(ctx -> {
        String mode = ctx.get("mode");
        int count = ctx.get("count");
        // ...
    })
    .register();
```

---

## 6. CONFIGURATION DX {#6-configuration-dx}

### 6.1 Etat de l'art

| Librairie | Utilise par | Forces | Faiblesses |
|-----------|------------|--------|------------|
| **Configurate** | Sponge, Velocity | Multi-format, node-based, records Java 14+ | API verbose, pas de validation integree, migration manuelle |
| **Typesafe Config** | Lightbend, Play | HOCON, merge, fallback | Pas de records, pas de validation, API datee |
| **Gestalt** | Standalone | Records natifs, multi-sources, type-safe | Moins connu |
| **ClearConfig** | Standalone | Type-safe, secrets, composable | Tres jeune |

### 6.2 Recommandation definitive : Config-as-Records natif dans Warp

**Philosophie** : La configuration doit etre un Java Record. Point final. Pas de `getNode("foo").getString()`.

```java
// DECLARATION de la config -- un Record Java
@PluginConfig(file = "config.yml", version = 2)
public record MyConfig(
    @Comment("Le serveur lobby par defaut")
    @Default("lobby")
    String defaultServer,

    @Comment("Nombre max de joueurs par serveur")
    @Default("100")
    @Range(min = 1, max = 10000)
    int maxPlayers,

    @Comment("Messages personnalises")
    Messages messages,

    @Comment("Liste des serveurs VIP")
    @Default("[]")
    List<String> vipServers
) {
    public record Messages(
        @Default("Bienvenue sur le serveur !")
        String welcome,

        @Default("Au revoir !")
        String farewell
    ) {}
}

// UTILISATION -- injection directe
@WarpPlugin(id = "my-plugin")
public class MyPlugin {
    @Inject private MyConfig config;  // Charge, valide, defauts appliques

    @OnEnable
    public void onEnable() {
        logger.info(config.messages().welcome());
    }

    // Hot-reload : ecouter les changements
    @Subscribe
    public void onConfigReload(PluginConfigReloadEvent<MyConfig> event) {
        MyConfig newConfig = event.newConfig();
        logger.info("Config rechargee, nouveau max: " + newConfig.maxPlayers());
    }
}
```

### 6.3 Fonctionnalites de configuration

| Fonctionnalite | Description | Comment |
|----------------|-------------|---------|
| **Auto-generation** | Le fichier YAML est genere automatiquement au premier lancement avec les `@Default` et `@Comment` | Annotation processor |
| **Validation** | `@Range`, `@Pattern`, `@NotBlank`, validations custom | Erreurs claires au chargement |
| **Migration** | `@PluginConfig(version = 2)` + classe `ConfigMigrator` | Inspiree de Flyway pour configs |
| **Hot-reload** | Watcher sur le fichier, re-parsing, re-validation, event | Configurable par plugin |
| **Multi-format** | YAML (defaut), TOML, JSON | Via Configurate sous le capot |
| **Secrets** | `@Secret` masque la valeur dans les logs et dumps | Securite par defaut |
| **Environnement** | `@Env("MY_VAR")` surcharge avec variable d'environnement | Cloud-native |

### 6.4 Systeme de migration de config

Inspire de Flyway (migrations de base de donnees) applique aux fichiers de configuration :

```java
@ConfigMigration(from = 1, to = 2)
public class MyConfigMigration implements ConfigMigrator {
    @Override
    public void migrate(ConfigNode node) {
        // Renommer une cle
        node.move("oldKey", "newKey");
        // Ajouter une nouvelle section
        node.set("messages.farewell", "Au revoir !");
        // Supprimer une cle obsolete
        node.remove("deprecated.setting");
    }
}
```

Quand un joueur met a jour le plugin, Warp detecte que la config est en version 1 et applique automatiquement la migration vers la version 2, avec backup automatique de l'ancienne config.

---

## 7. ISOLATION ET SECURITE {#7-isolation-et-securite}

### 7.1 Lecons des systemes existants

| Systeme | Strategie | Problemes |
|---------|-----------|-----------|
| **OSGi** | Isolation forte, resolution dynamique | Sur-complexe, class loading "hacks", incompatibilites bibliotheques |
| **PF4J** | Parent-last ClassLoader | Simple mais pas de resolution de dependances inter-plugins |
| **Paper (nouveau)** | ClassLoader isole par plugin | Bon mais rompt la compatibilite avec les anciens plugins |
| **Velocity** | ClassLoader partage (PAS d'isolation) | Les plugins peuvent ecraser les classes des autres |
| **JPMS** | Module system Java natif | Trop restrictif pour un ecosysteme de plugins dynamique |

### 7.2 Recommandation : Isolation pragmatique (PF4J++ ameliore)

**Ni OSGi (trop complexe), ni rien (Velocity). Un juste milieu.**

```
                    ┌──────────────────────────┐
                    │    Warp API ClassLoader   │
                    │  (warp-api.jar partagee)  │
                    └───────────┬──────────────┘
                                │ parent
              ┌─────────────────┼─────────────────┐
              │                 │                  │
    ┌─────────▼──────┐  ┌──────▼───────┐  ┌──────▼───────┐
    │ Plugin A        │  │ Plugin B     │  │ Plugin C     │
    │ ClassLoader     │  │ ClassLoader  │  │ ClassLoader  │
    │ (parent-first   │  │              │  │              │
    │  pour warp-api, │  │              │  │              │
    │  child-first    │  │              │  │              │
    │  pour le reste) │  │              │  │              │
    └────────────────┘  └──────────────┘  └──────────────┘
```

**Regles** :

1. **API Warp** : Toujours chargee depuis le parent (garantit la meme version pour tous)
2. **Dependances du plugin** : Chargees child-first (chaque plugin peut avoir ses propres versions de Gson, etc.)
3. **Services inter-plugins** : Via le registre de services DI (interfaces, pas implementations)
4. **Access control** : Seules les classes annotees `@Api` dans warp-api sont accessibles. Les classes internes de Warp sont invisibles.

### 7.3 Resolution de dependances inter-plugins

```
Plugin A (economy-api)     Plugin B (shop)
  @Provides EconomyService    @Inject EconomyService
        │                         ▲
        └─────────────────────────┘
           via Service Registry
           (interface partagee dans warp-api
            ou API exportee par Plugin A)
```

Le Service Registry fonctionne sur des interfaces. Plugin B ne voit JAMAIS les classes d'implementation de Plugin A. L'isolation est preservee.

### 7.4 API Surface Control

Inspire d'IntelliJ Platform et Caddy :

| Categorie | Accessible aux plugins | Exemples |
|-----------|----------------------|----------|
| **API publique** | OUI | `ProxyServer`, `Player`, `EventManager`, `CommandManager` |
| **API interne** | NON (invisible au classloader) | Pipeline Netty, protocol state machine, compression |
| **API experimentale** | OUI avec `@ExperimentalApi` warning | Nouvelles features en beta |
| **API deprecated** | OUI avec `@Deprecated` + delai de removal | Anciennes APIs avec migration guide |

L'annotation processor genere un WARNING a la compilation si un plugin utilise une API `@ExperimentalApi` ou `@Deprecated`.

### 7.5 Permissions plugins (sandbox legere)

Pas de `SecurityManager` (deprecated en Java 17, supprime en Java 24). A la place, une approche par convention :

```java
@WarpPlugin(
    id = "my-plugin",
    permissions = {
        @PluginPermission(NETWORK_ACCESS),    // Peut ouvrir des sockets
        @PluginPermission(FILE_WRITE),        // Peut ecrire sur le filesystem
        @PluginPermission(PLAYER_KICK),       // Peut kick des joueurs
    }
)
```

Ces permissions sont declaratives et documentees. Elles servent de :
- Documentation pour les admins (savoir ce que fait un plugin)
- Signal de confiance pour le marketplace
- Base pour un futur systeme de sandbox (JVM agents, etc.)

---

## 8. SDK ET OUTILLAGE {#8-sdk-et-outillage}

### 8.1 Gradle Plugin pour le developpement de plugins Warp

Inspire de Gradle Convention Plugins, paperweight-userdev, et Spigradle :

```kotlin
// build.gradle.kts d'un plugin Warp
plugins {
    id("dev.warp.plugin") version "1.0.0"
}

warp {
    apiVersion = "1.0.0"
    // C'est tout. Le reste est convention.
}

dependencies {
    // warp-api est automatiquement en compileOnly
    // l'annotation processor est automatiquement configure
}
```

**Ce que le Gradle plugin fait automatiquement** :
- Ajoute `warp-api` en `compileOnly`
- Configure l'annotation processor pour generer `warp-plugin.json`
- Configure le classpath de test avec MockWarp
- Genere les metadata de publication (compatibilite MC, versions, etc.)
- Valide les conventions (package unique, pas de classes dans le default package)
- Configure le JAR avec les bons manifests

### 8.2 Template de projet (Archetype)

```bash
# Creer un nouveau plugin Warp
$ warp init my-plugin
# ou via Gradle
$ gradle init --type warp-plugin

# Structure generee :
my-plugin/
  build.gradle.kts          # Pre-configure
  settings.gradle.kts
  src/main/java/.../MyPlugin.java    # Squelette annote
  src/main/java/.../MyConfig.java    # Config record exemple
  src/test/java/.../MyPluginTest.java # Premier test qui passe
  .github/workflows/ci.yml           # CI/CD pre-configure
```

### 8.3 Annotation Processor (warp-processor)

L'annotation processor est la cle d'une DX superieure. Il :

1. **Genere** `warp-plugin.json` a partir de `@WarpPlugin`
2. **Genere** le code de wiring DI (zero reflexion)
3. **Genere** le fichier de config par defaut a partir du Record `@PluginConfig`
4. **Valide** a la compilation :
   - Pas de `@Subscribe` sur une methode privee
   - Pas de `@Command` sans `@Permission`
   - Pas de dependances circulaires
   - Pas d'utilisation d'API interne
   - Pas d'`@Inject` sur un type non-resolvable
5. **Genere des erreurs claires** : pas des stacktraces, mais des messages avec la ligne, le fichier, et une suggestion de fix

### 8.4 Support IDE

- **IntelliJ** : Le plugin Minecraft Development (DemonWav) peut etre etendu pour supporter Warp, ou un plugin IntelliJ dedie peut etre cree
- **Completions** : L'annotation processor genere les metadonnees necessaires pour que l'IDE complete les event types, command arguments, config keys
- **Inspections** : Detecter les patterns problematiques (bloquer dans un event listener, oublier de deregistrer, etc.)

### 8.5 CLI Warp

```bash
# Gestion des plugins
$ warp plugin list                    # Liste les plugins installes
$ warp plugin install economy-api     # Installe depuis le registry
$ warp plugin update --all            # Met a jour tous les plugins
$ warp plugin info my-plugin          # Details, deps, permissions

# Developpement
$ warp init my-plugin                 # Nouveau projet
$ warp dev                            # Lance un proxy local avec hot-reload
$ warp test                           # Lance les tests du plugin
$ warp publish                        # Publie sur le registry
```

---

## 9. TESTING FRAMEWORK (MockWarp) {#9-testing-framework}

### 9.1 Inspiration : MockBukkit

MockBukkit a prouve que le testing unitaire de plugins MC est viable. Warp doit livrer un equivalent natif des le jour 1.

### 9.2 Design de MockWarp

```java
class MyPluginTest {

    private MockWarpServer server;
    private MyPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockWarp.create();
        plugin = server.loadPlugin(MyPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockWarp.unmock();
    }

    @Test
    void testLobbyCommand() {
        // Arrange
        MockPlayer player = server.addPlayer("Steve");
        MockServer lobby = server.addBackend("lobby");

        // Act
        player.executeCommand("lobby");

        // Assert
        assertThat(player.getCurrentServer()).isEqualTo(lobby);
        assertThat(player.lastMessage()).contains("Transfere");
    }

    @Test
    void testLoginEvent() {
        // Arrange
        server.loadPlugin(MyPlugin.class);

        // Act
        MockPlayer player = server.addPlayer("Steve");

        // Assert
        assertThat(player.lastMessage()).contains("Bienvenue");
    }

    @Test
    void testConfigReload() {
        // Arrange
        MyConfig original = plugin.getConfig();

        // Act
        server.reloadPluginConfig(plugin, """
            defaultServer: hub
            maxPlayers: 200
            """);

        // Assert
        MyConfig reloaded = plugin.getConfig();
        assertThat(reloaded.defaultServer()).isEqualTo("hub");
        assertThat(reloaded.maxPlayers()).isEqualTo(200);
    }
}
```

### 9.3 Fonctionnalites de MockWarp

| Fonctionnalite | Description |
|----------------|-------------|
| **MockPlayer** | Joueur simule, peut executer des commandes, envoyer des messages, se connecter/deconnecter |
| **MockServer** | Backend simule, track les joueurs connectes |
| **MockEventBus** | Verifie quels events ont ete fires, dans quel ordre |
| **MockConfig** | Permet de fournir une config custom pour les tests |
| **MockScheduler** | Avance le temps manuellement pour tester les taches planifiees |
| **Assertions fluides** | API d'assertions specifiques a Warp (AssertJ style) |

### 9.4 Integration testing

En plus des tests unitaires, un framework d'integration testing permettra de tester contre un vrai proxy :

```java
@WarpIntegrationTest
class MyPluginIntegrationTest {

    @WarpInstance
    private WarpProxy proxy;

    @Test
    void testRealConnection() {
        // Lance un vrai proxy avec le plugin
        // Simule une connexion MC
        // Verifie le comportement end-to-end
    }
}
```

---

## 10. DISTRIBUTION ET MARKETPLACE {#10-distribution-et-marketplace}

### 10.1 Modeles existants

| Plateforme | Forces | Faiblesses |
|------------|--------|------------|
| **Modrinth** | Open API, metadata riche, channels de release | Pas specifique proxy |
| **Hangar** (PaperMC) | Fait pour Paper/Velocity, review process | Limite a l'ecosysteme Paper |
| **SpigotMC** | Enorme communaute | UI datee, pas d'API moderne, pas de versionning semantique |
| **Polymart** | Paiements, licenses | Ferme, pas d'API publique |

### 10.2 Recommandation : API compatible Modrinth/Hangar + CLI natif

Warp ne devrait PAS creer son propre marketplace au debut. A la place :

1. **Standard de metadata** dans `warp-plugin.json` compatible avec Modrinth et Hangar
2. **CLI `warp publish`** qui publie sur Modrinth/Hangar automatiquement
3. **CLI `warp plugin install`** qui cherche dans Modrinth/Hangar
4. **A long terme** : Si l'ecosysteme grandit, envisager un registry dedie

### 10.3 Metadata de compatibilite

```json
{
  "id": "my-plugin",
  "version": "1.2.0",
  "warp-api": ">=1.0.0",
  "minecraft": ["1.21", "1.21.1", "1.21.2"],
  "java": ">=21",
  "channels": {
    "release": "1.2.0",
    "beta": "1.3.0-beta.1"
  },
  "dependencies": {
    "required": [
      { "id": "warp-api", "version": ">=1.0.0" }
    ],
    "optional": [
      { "id": "luckperms", "version": ">=5.4" }
    ],
    "incompatible": [
      { "id": "old-economy", "reason": "Remplace par economy-api v2" }
    ]
  }
}
```

---

## 11. QUALITE DES MESSAGES D'ERREUR {#11-qualite-des-erreurs}

### 11.1 Philosophie : Chaque erreur est une opportunite d'enseigner

Inspire de Rust et Elm : les erreurs ne doivent JAMAIS etre cryptiques. Chaque erreur doit :
1. Dire CE QUI s'est passe
2. Dire OU ca s'est passe
3. Dire POURQUOI
4. Suggerer COMMENT corriger

### 11.2 Exemples concrets

**MAUVAIS (Velocity actuel)** :
```
[ERROR]: Unable to load plugin velocity-plugin.jar
com.google.inject.CreationException: Unable to create injector, see the following errors:
1) No implementation for com.velocitypowered.api.proxy.ProxyServer was bound.
  while locating com.velocitypowered.api.proxy.ProxyServer
    for the 2nd parameter of com.example.MyPlugin.<init>(MyPlugin.java:15)
```

**BON (Warp)** :
```
[ERROR] Plugin 'my-plugin' (v1.0.0) failed to load
  --> MyPlugin.java:15

  Cause: Cannot inject 'DatabaseService' -- no provider found.

  The field 'database' in MyPlugin requires a DatabaseService,
  but no plugin provides this service.

  Suggestions:
    - Add a dependency on a plugin that provides DatabaseService
    - If the dependency is optional, use @Inject @Optional DatabaseService
    - If you provide this service yourself, annotate the factory with @Provides

  See: https://docs.warp.dev/plugins/dependency-injection#missing-service
```

### 11.3 Categories d'erreurs avec messages clairs

| Categorie | Exemple de message clair |
|-----------|------------------------|
| **Dependance manquante** | "Plugin 'shop' requires 'economy' v2.0+, but only v1.3 is installed" |
| **Config invalide** | "config.yml line 12: 'maxPlayers' must be between 1 and 10000, got -5" |
| **Event listener invalide** | "@Subscribe method 'onLogin' in MyListener must be public (found private)" |
| **Commande en conflit** | "Command '/lobby' already registered by plugin 'hub-manager'. Use @Command(\"lobby2\") or contact the other plugin author" |
| **API deprecated** | "MyPlugin uses Player.getAddress() which will be removed in Warp 2.0. Use Player.remoteAddress() instead. See migration guide: ..." |
| **Classloader** | "Plugin 'my-plugin' uses Gson 2.8 but Warp API requires Gson 2.10+. Shade your own version or update." |
| **Cycle de dependances** | "Circular dependency detected: A -> B -> C -> A. Break the cycle by making one dependency optional or using lazy injection." |

---

## 12. ROADMAP D'IMPLEMENTATION {#12-roadmap}

### Phase 4A : Core Plugin System (Prerequisites)

| Element | Priorite | Effort | Description |
|---------|----------|--------|-------------|
| `warp-api` module | P0 | M | Interfaces publiques, annotations, event types |
| Annotation processor | P0 | L | Genere metadata, DI wiring, validation compile-time |
| Plugin ClassLoader | P0 | M | Isolation parent-first/child-first |
| Plugin lifecycle manager | P0 | M | DISCOVERED -> LOADED -> ENABLED -> DISABLED -> UNLOADED |
| DI container maison | P0 | M | Resolution des @Inject, service registry |
| Event bus avec garanties | P0 | L | Ordre strict, async, timeouts, phase-aware |

### Phase 4B : DX Layer

| Element | Priorite | Effort | Description |
|---------|----------|--------|-------------|
| Config-as-Records | P1 | M | @PluginConfig, validation, defauts, migration |
| Command framework | P1 | L | Annotations + Brigadier bridge + completions |
| Gradle plugin `dev.warp.plugin` | P1 | M | Convention plugin pour le dev de plugins |
| MockWarp test framework | P1 | L | MockPlayer, MockServer, MockEventBus |
| Messages d'erreur | P1 | M | Systeme de diagnostic contextuel |

### Phase 4C : Ecosystem

| Element | Priorite | Effort | Description |
|---------|----------|--------|-------------|
| Template de projet / CLI init | P2 | S | Scaffolding rapide |
| Hot-reload dev mode | P2 | M | Recharge du plugin sans restart proxy |
| Documentation interactive | P2 | M | Javadoc + exemples + guides |
| Registry integration | P2 | M | Modrinth/Hangar publish/install |
| IntelliJ plugin | P3 | L | Inspections, completions, templates |

**Legende effort** : S = 1-2 semaines, M = 2-4 semaines, L = 4-8 semaines

---

## RESUME DES DECISIONS DEFINITIVES

| Decision | Choix | Justification |
|----------|-------|---------------|
| **Declaration plugin** | `@WarpPlugin` + annotation processor | Zero boilerplate, validation compile-time, inspire Sponge |
| **DI** | Maison legere + Avaje optionnel | Compile-time, zero reflexion, erreurs claires, pas lourd comme Guice |
| **Events** | Async avec CompletableFuture/Continuation + garanties d'ordre | Corrige TOUS les defauts de Velocity/BungeeCord |
| **Commandes** | Annotations-first, Cloud en moteur interne | Simple pour 90%, puissant pour 10%, inspire Lamp |
| **Configuration** | Records Java + annotation processor | Type-safe, auto-generation, validation, migration, hot-reload |
| **Isolation** | ClassLoader par plugin, parent-first pour API | Pragmatique, ni OSGi (complexe) ni rien (Velocity) |
| **Testing** | MockWarp natif | Comme MockBukkit mais pour le proxy, livre avec le SDK |
| **Outillage** | Gradle convention plugin + CLI | Zero config pour demarrer, inspire Gradle/Paper |
| **Distribution** | Compatible Modrinth/Hangar | Pas de marketplace custom, ecosysteme existant |
| **Erreurs** | Style Rust/Elm : contextuelles, suggestives | Chaque erreur enseigne, jamais cryptique |

---

## SOURCES

### Systemes d'extension etudies
- [PF4J - Plugin Framework for Java](https://github.com/pf4j/pf4j)
- [Caddy Module System Architecture](https://caddyserver.com/docs/architecture)
- [Caddy - Extending Caddy](https://caddyserver.com/docs/extending-caddy)
- [IntelliJ Extension Points](https://plugins.jetbrains.com/docs/intellij/plugin-extension-points.html)
- [IntelliJ Plugin Services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)
- [Gradle Convention Plugins](https://docs.gradle.org/current/userguide/implementing_gradle_plugins_convention.html)
- [VS Code Activation Events](https://code.visualstudio.com/api/references/activation-events)
- [VS Code Contribution Points](https://code.visualstudio.com/api/references/contribution-points)
- [WordPress Plugin Best Practices](https://developer.wordpress.org/plugins/plugin-basics/best-practices/)
- [Plugin Architectures with Layrry and Java Modules](https://www.morling.dev/blog/plugin-architectures-with-layrry-and-the-java-module-system/)

### Injection de dependances
- [Avaje Inject](https://avaje.io/inject/)
- [Avaje Inject Introduction (Baeldung)](https://www.baeldung.com/avaje-inject)
- [Comparing DI Frameworks: Spring, Guice, Dagger](https://medium.com/@AlexanderObregon/comparing-dependency-injection-frameworks-spring-guice-and-dagger-a614dccd5859)
- [Advanced DI: Spring, Guice, Dagger 2](https://www.javacodegeeks.com/2025/02/advanced-dependency-injection-comparing-spring-guice-and-dagger-2.html)
- [Java ServiceLoader Beyond Plugins](https://blog.frankel.ch/rediscovering-java-serviceloader/)
- [Java SPI (Baeldung)](https://www.baeldung.com/java-spi)

### Frameworks de commandes
- [Cloud Command Framework (Incendo)](https://github.com/Incendo/cloud)
- [Cloud Minecraft Integration](https://github.com/Incendo/cloud-minecraft)
- [Lamp Command Framework](https://github.com/Revxrsal/Lamp)
- [Brigadier (Mojang)](https://github.com/Mojang/brigadier)

### Configuration
- [Configurate (SpongePowered)](https://github.com/SpongePowered/Configurate)
- [Gestalt Configuration Library](https://github.com/gestalt-config/gestalt)
- [Typesafe Config (Lightbend)](https://github.com/lightbend/config)
- [tscfg - Type-safe Config Generation](https://github.com/carueda/tscfg)

### Isolation et classloading
- [Java Plugins with Isolating ClassLoaders (Adevinta)](https://adevinta.com/techblog/java-plugins-with-isolating-class-loaders/)
- [Cytodynamics ClassLoader Isolation (LinkedIn)](https://github.com/linkedin/Cytodynamics)
- [PF4J PluginClassLoader](https://pf4j.org/doc/class-loading.html)
- [OSGi Painful Road to Modularity](https://blog.enioka.com/2025/06/18/a-painful-road-to-java-modularity/)
- [Java Security Post-SecurityManager](https://inside.java/2021/04/23/security-and-sandboxing-post-securitymanager/)
- [Paper Plugins (New Loader)](https://docs.papermc.io/paper/reference/paper-plugins/)

### Testing
- [MockBukkit](https://github.com/MockBukkit/MockBukkit)
- [MockBukkit Docs](https://docs.mockbukkit.org/)
- [Jenkins Test Harness](https://github.com/jenkinsci/jenkins-test-harness)
- [Maven Plugin Testing Harness](https://maven.apache.org/plugin-testing/maven-plugin-testing-harness/)

### Distribution et marketplace
- [Modrinth - Plugins & Resource Packs](https://blog.modrinth.com/p/plugins-resource-packs)
- [Hangar - PaperMC Plugin Repository](https://hangar.papermc.io/)

### Qualite des erreurs
- [Diagnostics are UX: Designing Developer-Friendly Error Systems](https://medium.com/@RameshRavone/diagnostics-are-ux-designing-developer-friendly-error-systems-7b7b24563909)
- [Compiler Errors for Humans (Elm)](https://elm-lang.org/news/compiler-errors-for-humans)
- [Rust RFC: Error Message Format](https://rust-lang.github.io/rfcs/1644-default-and-expanded-rustc-errors.html)
- [Comparing Compiler Errors Across Languages](https://www.amazingcto.com/developer-productivity-compiler-errors/)

### Proxys MC analyses
- [Velocity Plugin Basics](https://docs.papermc.io/velocity/dev/api-basics/)
- [Velocity Configuration API Issue #326](https://github.com/PaperMC/Velocity/issues/326)
- [Velocity Event API](https://docs.papermc.io/velocity/dev/event-api/)
- [Gate Proxy Developer Docs](https://gate.minekube.com/developers/)
- [Gate Plugin Template](https://github.com/minekube/gate-plugin-template)

### Outillage et SDK
- [Spigot Plugin.yml Annotations](https://www.spigotmc.org/wiki/spigot-plugin-yml-annotations/)
- [Awesome Annotation Processing](https://github.com/gunnarmorling/awesome-annotation-processing)
- [Gradle Plugin Starter Template](https://github.com/int128/gradle-plugin-starter)
- [PaperMC paperweight-userdev](https://docs.papermc.io/paper/dev/userdev/)
- [Minecraft Development IntelliJ Plugin](https://plugins.jetbrains.com/plugin/8327-minecraft-development)
