# Strategie de migration vers Warp Proxy

**Date** : 31 mars 2026
**Objectif** : Definir une strategie de migration complete pour les reseaux Minecraft passant de Velocity/BungeeCord a Warp, avec outils, documentation, communication et gestion des risques.
**Sources** : Velocity/BungeeCord migration paths, Snap (Phoenix616), TransferProxy, Dropbox Nginx->Envoy, Slack HAProxy->Envoy, Reddit HAProxy->Envoy, Express->Fastify, Paper migration from Spigot, Strangler Fig Pattern, Gate/Infrarust.

---

## TABLE DES MATIERES

1. [Lecons des migrations existantes](#1-lecons-des-migrations-existantes)
2. [Strategie globale : les 4 phases](#2-strategie-globale)
3. [Outils de migration a construire](#3-outils-de-migration)
4. [Couche de compatibilite plugins](#4-compatibilite-plugins)
5. [Migration des donnees](#5-migration-des-donnees)
6. [Patterns de migration graduelle](#6-migration-graduelle)
7. [Guide de migration pour les developpeurs de plugins](#7-migration-developpeurs)
8. [Structure de la documentation](#8-documentation)
9. [Strategie de communication](#9-communication)
10. [Evaluation des risques](#10-risques)
11. [Chronologie](#11-chronologie)
12. [Decisions et recommandations finales](#12-decisions)

---

## 1. LECONS DES MIGRATIONS EXISTANTES {#1-lecons-des-migrations-existantes}

### 1.1 Comment Paper a reussi la migration depuis Spigot

**Strategie** : Compatibilite descendante totale.

Paper est le gold standard de la migration MC :
- **Drop-in replacement** : Remplacer le JAR Spigot par le JAR Paper suffit. Les plugins Spigot/Bukkit fonctionnent sans modification
- **Meme API** : Paper etend l'API Bukkit/Spigot au lieu de la remplacer. Les plugins existants continuent de fonctionner, les nouveaux plugins profitent des ajouts Paper
- **Configuration compatible** : Les fichiers `server.properties`, `spigot.yml`, `bukkit.yml` sont lus tels quels. Paper ajoute ses propres fichiers (`paper-global.yml`, `paper-world-defaults.yml`)
- **Worlds compatibles** : Aucune conversion necessaire, le format de monde est identique

**Pourquoi ca a marche** :
- Zero friction a l'adoption -- un admin peut tester Paper en 30 secondes
- Pas de "fork decision" -- on ne perd rien en migrant
- Les developpeurs de plugins n'ont rien a changer pour supporter Paper

**Lecon pour Warp** : La compatibilite totale API est impossible (Warp a une architecture fondamentalement differente). Mais la FRICTION de migration doit etre minimale. Chaque minute de travail supplementaire pour migrer est un frein a l'adoption.

### 1.2 Comment Velocity a gere la migration depuis BungeeCord

**Strategie** : Rupture nette, API propre.

Velocity a fait le choix inverse de Paper :
- **API completement differente** : Aucun plugin BungeeCord ne fonctionne sur Velocity
- **Configuration differente** : `velocity.toml` au lieu de `config.yml` (BungeeCord)
- **Forwarding different** : Modern forwarding (HMAC) vs legacy (texte clair dans le handshake)
- **Pas d'outil de migration** : Aucun convertisseur de config officiel, aucun guide de migration officiel structure
- **Migration plugin-par-plugin** : Les admins doivent trouver l'equivalent Velocity de chaque plugin BungeeCord

**Ce qui a marche** :
- L'API propre a attire les developpeurs fatigues des defauts de BungeeCord
- Les gains de performance (8x throughput) justifiaient l'effort
- La securite (modern forwarding) etait un argument decisif

**Ce qui n'a PAS marche** :
- Migration lente (5+ ans et BungeeCord est encore massivement utilise en 2026)
- Le projet Snap (compatibilite BungeeCord) a echoue (voir section 4)
- Beaucoup de reseaux restent sur BungeeCord par inertie (trop de plugins a remplacer)
- Aucun outil officiel de migration de configuration
- Pas de guide communautaire structure (le seul est le repo `Switch-to-Velocity` de Syrent, qui est surtout une liste de plugins alternatifs)

**Lecon pour Warp** : L'API propre est le bon choix. Mais Velocity a sous-estime le cout de migration pour les admins. Warp doit compenser la rupture API par des outils et de la documentation de migration de premier ordre.

### 1.3 Comment Dropbox a migre de Nginx a Envoy

**Strategie** : Deploiement parallele + migration par vagues sur 6+ mois.

Dropbox gerait des dizaines de millions de connexions simultanees, des millions de requetes/seconde, et des terabits de bande passante :

1. **Phase parallele** : Nginx et Envoy ont tourne cote a cote pendant 6+ mois
2. **Traffic shifting DNS** : Basculement progressif du trafic par DNS
3. **Migration par workload** :
   - D'abord les services gRPC haut-debit (plus simple, moins de edge cases HTTP)
   - Puis les services metadata haut-RPS
   - Puis les notifications et telemetrie
   - Puis le trafic API mixte
   - Enfin www.dropbox.com (le plus risque)
4. **Outil de test interne "Hulk"** : Benchmarks comparatifs sous charge reelle
5. **Rollback par DNS** : Possibilite de revenir a Nginx en quelques minutes

**Problemes rencontres** :
- Normalisation des slashes (Nginx merge les slashes, Envoy non)
- Format des headers Host (avec/sans port)
- Circuit breakers mal configures causant des coupures
- Pas de buffering disque dans Envoy

**Resultats** : Liberation de 60% des serveurs occupes par Nginx. Aucune panne notable.

**Lecon pour Warp** : La migration par vagues est le modele a suivre. Commencer par les workloads les plus simples, finir par les plus critiques. Le rollback doit etre instantane.

### 1.4 Comment Slack a migre de HAProxy a Envoy

**Strategie** : Stack parallele + basculement progressif par region.

Slack a migre des millions de WebSockets concurrentes :
- Stack Envoy identique a HAProxy deployee en parallele
- Weighted routing pour basculer le trafic : 10% -> 25% -> 50% -> 75% -> 100%
- Deploiement region par region
- Rollback immediat en re-basculant les poids

**Avantage cle d'Envoy** : Clusters et endpoints dynamiques sans reload, hot restart sans drop de connexions.

**Lecon pour Warp** : Le basculement progressif par pourcentage (10/25/50/75/100) est le pattern ideal. Les reseaux MC devraient pouvoir migrer un pourcentage de joueurs a la fois.

### 1.5 Comment Fastify a gere la migration depuis Express

**Strategie** : Bridge plugin + migration route par route.

Fastify fournit `fastify-express`, un plugin qui permet d'utiliser du middleware Express dans Fastify :
- Migration incrementale : garder l'ancien code et migrer route par route
- Reverse proxy entre les deux serveurs pendant la transition
- Remplacement progressif du middleware Express par des plugins Fastify

**Lecon pour Warp** : Un bridge temporaire est utile pour les early adopters, MAIS ne doit pas devenir permanent (voir les lecons de Snap ci-dessous).

### 1.6 Le Strangler Fig Pattern

Application du pattern architectural a la migration de proxy :

1. **Facade** : Le load balancer (HAProxy/DNS) devant les deux proxys (Velocity + Warp)
2. **Migration incrementale** : Router un server group a la fois vers Warp
3. **Coexistence** : Les deux proxys coexistent pendant toute la migration
4. **Retrait progressif** : Une fois tous les server groups migres, l'ancien proxy est retire

**Application concrete pour Warp** :

```
[Joueurs] -> [HAProxy / DNS / Transfer Packet]
                |                    |
                v                    v
            [Velocity]           [Warp]
              |    |              |    |
              v    v              v    v
           [Lobby] [SkyWars]  [BedWars] [Hub]
```

Le Transfer Packet (1.20.5+) ouvre une possibilite supplementaire : un joueur connecte a Velocity peut etre redirige vers Warp sans deconnexion visible, si le client supporte le protocol.

---

## 2. STRATEGIE GLOBALE : LES 4 PHASES {#2-strategie-globale}

### Phase 0 : Pre-migration (avant le lancement public de Warp)

**Objectif** : Rendre la migration POSSIBLE et DOCUMENTEE avant que quiconque n'essaie.

| Action | Priorite | Statut |
|--------|----------|--------|
| Outil `warp migrate --from velocity` | P0 | A construire |
| Outil `warp migrate --from bungeecord` | P0 | A construire |
| Documentation "Migration en 10 minutes" | P0 | A ecrire |
| Tableau d'equivalence plugins (top 50) | P0 | A compiler |
| API comparison guide Velocity -> Warp | P0 | A ecrire |
| Template de plugin "Hello World" | P0 | A construire |

### Phase 1 : Early Adopters (alpha/beta)

**Objectif** : Valider la migration avec des reseaux reels, en mode dual-proxy.

| Action | Priorite |
|--------|----------|
| Programme early adopter (5-10 reseaux) | P0 |
| Support dual-proxy (Warp + Velocity en parallele) | P0 |
| Canal Discord dedie migration | P1 |
| Collecte de feedback structure | P0 |
| Fix des bugs de migration remontes | P0 |

**Criteres de passage en Phase 2** :
- Au moins 3 reseaux ont migre avec succes en dual-proxy
- Zero perte de donnees reportee
- Rollback fonctionne en < 5 minutes
- Les top 20 plugins ont un equivalent Warp ou fonctionnent via le bridge

### Phase 2 : Adoption progressive (release stable)

**Objectif** : Rendre la migration accessible a tous, avec documentation complete.

| Action | Priorite |
|--------|----------|
| Documentation complete de migration | P0 |
| Videos tutoriel migration | P1 |
| Outil de benchmark compare (Warp vs Velocity) | P1 |
| Plugin migration toolkit pour developpeurs | P0 |
| Articles de blog / case studies early adopters | P1 |

### Phase 3 : Migration de masse (6-12 mois post-release)

**Objectif** : Devenir le choix par defaut pour les nouveaux reseaux ET faciliter la migration des anciens.

| Action | Priorite |
|--------|----------|
| Hosting providers integration (Pterodactyl, PufferPanel) | P1 |
| Templates Docker optimises | P1 |
| "Warp Certified" plugins programme | P2 |
| Conference talks / SpigotMC threads | P2 |

---

## 3. OUTILS DE MIGRATION A CONSTRUIRE {#3-outils-de-migration}

### 3.1 `warp migrate` -- CLI de migration automatisee

Outil en ligne de commande integre a Warp.

#### 3.1.1 `warp migrate --from velocity [chemin]`

**Input** : Dossier d'une installation Velocity existante.
**Output** : Configuration Warp equivalente + rapport de migration.

**Conversions effectuees** :

| Velocity (`velocity.toml`) | Warp (format a definir) | Notes |
|---|---|---|
| `bind` | `bind` | Direct |
| `motd` | `motd` | Direct (MiniMessage compatible) |
| `show-max-players` | `max-players` | Direct |
| `online-mode` | `online-mode` | Direct |
| `player-info-forwarding-mode` | `forwarding.mode` | Mapping `modern` -> `hmac`, `legacy` -> `legacy`, `none` -> `none` |
| `forwarding-secret-file` | `forwarding.secret-file` | Direct, valider que le fichier existe |
| `[servers]` | `servers[]` | TOML table -> liste structuree |
| `try` (ordre de connexion) | `servers[].priority` | Convertir la liste ordonnee en priorites numeriques |
| `[forced-hosts]` | `virtual-hosts[]` | Mapping direct |
| `compression-threshold` | `network.compression-threshold` | Direct |
| `compression-level` | `network.compression-level` | Direct |
| `login-ratelimit` | `security.login-ratelimit` | Direct |
| `query-enabled` + `query-port` | `query.enabled` + `query.port` | Direct |

**Actions supplementaires** :
- Copier le `forwarding-secret` si present
- Scanner le dossier `plugins/` et generer un rapport de compatibilite plugin
- Lister les plugins sans equivalent Warp connu
- Generer un `migration-report.txt` avec les actions manuelles restantes

#### 3.1.2 `warp migrate --from bungeecord [chemin]`

**Input** : Dossier d'une installation BungeeCord/Waterfall.
**Output** : Configuration Warp + rapport.

**Conversions supplementaires** (en plus de celles ci-dessus) :

| BungeeCord (`config.yml`) | Warp | Notes |
|---|---|---|
| `listeners[].host` | `bind` | Supporter le multi-listener |
| `listeners[].priorities` | `servers[].priority` | Mapping |
| `listeners[].forced_hosts` | `virtual-hosts[]` | Mapping |
| `ip_forward` | `forwarding.mode` | `true` -> `legacy`, `false` -> `none` |
| `groups` | N/A | Warp n'a pas de groupes builtin, generer un warning |
| `permissions` | N/A | Warning : utiliser LuckPerms ou equivalent |
| `connection_throttle` | `security.login-ratelimit` | Mapping |
| `timeout` | `network.read-timeout` | Mapping |

**Avertissements specifiques BungeeCord** :
- "BungeeCord utilise ip_forward (legacy). Warp recommande le mode HMAC. Vos backends doivent supporter modern forwarding (Paper 1.20.5+, Fabric+FabricProxy Lite, etc.)"
- "Les permissions builtin BungeeCord n'existent pas dans Warp. Installez un plugin de permissions."
- "Les groupes BungeeCord n'existent pas dans Warp."

#### 3.1.3 `warp migrate --check`

**Mode dry-run** : Analyse l'installation existante sans rien creer.

```
$ warp migrate --check --from velocity /opt/velocity

=== Rapport de compatibilite migration ===

Configuration :
  [OK] velocity.toml lisible
  [OK] forwarding-secret present
  [OK] Compression threshold : 256 (supportee)
  [WARN] online-mode: false -- Warp supporte, mais assurez-vous d'avoir un plugin d'authentification

Plugins (12 detectes) :
  [OK] LuckPerms v5.4 -> Supporte nativement sur Warp
  [OK] MiniMOTD v2.1 -> Equivalent Warp disponible
  [OK] ViaVersion v5.0 -> Compatible Warp (bridge Velocity API)
  [WARN] TAB v4.0 -> Pas encore d'equivalent Warp. Utiliser le bridge Velocity temporairement
  [FAIL] CustomPlugin-1.0.jar -> Plugin inconnu, migration manuelle requise

Serveurs backends (5 detectes) :
  [OK] lobby (paper 1.21.4) -- Supporte modern forwarding
  [OK] survival (paper 1.21.4) -- Supporte modern forwarding
  [WARN] creative (spigot 1.20.1) -- Verifier le support de modern forwarding

Actions requises :
  1. Migrer le plugin TAB ou activer le bridge Velocity
  2. Verifier que CustomPlugin-1.0.jar a un equivalent Warp
  3. Mettre a jour creative vers Paper pour modern forwarding

Estimation : migration faisable en ~30 minutes
```

### 3.2 Plugin Scanner

Module interne a `warp migrate` qui analyse le dossier `plugins/` :

1. **Lecture des metadonnees** : `velocity-plugin.json`, `bungee.yml`, `plugin.yml`
2. **Matching contre une base de donnees** : Liste maintenue par la communaute des equivalences plugins
3. **Analyse des imports** : Scanner les `.class` du JAR pour detecter les API utilisees (BungeeCord API, Velocity API, APIs specifiques)
4. **Score de compatibilite** : Vert (supporte nativement), Jaune (bridge possible), Rouge (migration manuelle)

Format de la base de donnees d'equivalences (JSON maintenu sur GitHub) :

```json
{
  "plugins": [
    {
      "bungeecord": "LuckPerms-Bungee",
      "velocity": "LuckPerms-Velocity",
      "warp": "LuckPerms-Warp",
      "status": "native",
      "notes": "Meme JAR multi-plateforme depuis v5.4"
    },
    {
      "bungeecord": "BungeeTabListPlus",
      "velocity": "TAB",
      "warp": null,
      "status": "no-equivalent",
      "notes": "Bridge Velocity utilisable temporairement"
    }
  ]
}
```

### 3.3 Benchmark Comparatif

Outil optionnel pour prouver les gains de performance :

```
$ warp benchmark --compare velocity --players 1000 --duration 60s

=== Benchmark Warp vs Velocity ===
Duree : 60 secondes | Joueurs simules : 1000

                        Velocity        Warp            Gain
Connexions/sec          450             1,200           +166%
Latence P50             12ms            4ms             -66%
Latence P99             89ms            23ms            -74%
CPU moyen               78%             31%             -60%
Memoire pic             1.2 GB          380 MB          -68%
Paquets/sec relaye      850,000         2,400,000       +182%

Note : Le gain de Warp vient principalement du blind forwarding
(les paquets non-inspectes ne sont pas decompresses/recompresses)
```

---

## 4. COUCHE DE COMPATIBILITE PLUGINS {#4-compatibilite-plugins}

### 4.1 Pourquoi Snap a echoue

Le projet Snap de Phoenix616 (run BungeeCord plugins on Velocity) est le precedent le plus pertinent. Analyse de son echec :

**Architecture de Snap** :
- Instance separee du plugin manager BungeeCord
- Traduction a la volee des appels API BungeeCord -> Velocity
- Traduction des evenements dans les deux sens

**Raisons de l'echec** :

| Probleme | Detail |
|----------|--------|
| **Surface API trop large** | L'API BungeeCord est immense et mal documentee. Chaque methode non-implementee = crash |
| **APIs internes utilisees** | Beaucoup de plugins BungeeCord utilisent `net.md_5.bungee` (classes internes), pas juste l'API publique |
| **Differences architecturales** | Velocity n'a pas de groupes, pas de permissions builtin, pas de scoreboards, pas de reconnect handler |
| **Performance** | Traduction a la volee = overhead significatif sur chaque appel |
| **Maintenance insoutenable** | Chaque mise a jour de Velocity ou BungeeCord peut casser la couche de traduction |
| **Faux sentiment de securite** | Les plugins "fonctionnent" en apparence mais ont des bugs subtils (events manques, timing different) |

**Verdict** : Un adaptateur BungeeCord complet est un puits sans fond. 80% du travail est dans les 20% de cas edge.

### 4.2 Recommandation pour Warp : Bridge Velocity TEMPORAIRE + API propre

Warp se positionnant APRES Velocity (pas apres BungeeCord), l'ecosysteme cible est celui de Velocity. La plupart des plugins actifs en 2026 ont deja une version Velocity.

#### Bridge Velocity (temporaire, 12-18 mois de support)

**Scope volontairement limite** : Ne PAS tenter de supporter 100% de l'API Velocity. Supporter les 80% les plus utilises :

| API Velocity | Support Bridge | Notes |
|---|---|---|
| `ProxyServer` (serveurs, joueurs) | Oui | Mapping direct |
| `Player` (messages, kick, transfer) | Oui | Mapping direct |
| `RegisteredServer` | Oui | Mapping direct |
| `EventManager` (subscribe, fire) | Oui | Traduction evenements |
| `CommandManager` | Oui | Mapping Brigadier |
| `PluginManager` (metadonnees) | Oui | Read-only |
| `ProxyConfig` | Partiel | Defaults sensibles pour les champs manquants |
| `ChannelRegistrar` (plugin messages) | Oui | Mapping direct |
| `TabList` API | Non | API trop differente, pas de traduction utile |
| `ServerConnection` internals | Non | Architecture differente |
| `ResourcePackInfo` | Oui | Mapping direct |

**Regles du bridge** :
1. **Annonce d'obsolescence** : Chaque appel au bridge log un warning au demarrage (pas a chaque appel) : "Le plugin X utilise le bridge Velocity. Contactez le developpeur pour une version Warp native."
2. **Metrics de bridge** : Compter combien de plugins utilisent le bridge pour mesurer l'adoption native
3. **Sunset annonce** : Le bridge sera retire dans Warp 2.0 (ou equivalement 12-18 mois apres la release stable)
4. **Pas de bridge BungeeCord** : Trop eloigne architecturalement. Les plugins BungeeCord-only devront migrer vers Warp nativement

#### API propre Warp (long terme)

L'API Warp doit etre suffisamment meilleure que celle de Velocity pour justifier la migration. Les avantages concurrentiels identifies dans la recherche plugin-dx :

- Annotation-driven (zero boilerplate, annotation processor genere les metadonnees)
- Injection de dependances native (pas de Guice, pas de constructeur chicken-egg)
- Configuration type-safe par records
- Testing framework integre (MockWarp)
- Evenements avec garanties d'ordre et de phase
- Messages d'erreur pedagogiques (style Rust/Elm)

### 4.3 Matrice de decision : Quand utiliser quoi

```
Le plugin a une version Warp native ?
  -> OUI : Utiliser la version native
  -> NON : Le plugin a une version Velocity ?
      -> OUI : Utiliser via le bridge Velocity (temporaire)
      -> NON : Le plugin a une version BungeeCord seulement ?
          -> OUI : Pas de bridge. Chercher un equivalent Warp/Velocity,
                   ou contacter le dev du plugin
          -> NON : Plugin custom interne ?
              -> Migrer vers l'API Warp (voir guide section 7)
```

---

## 5. MIGRATION DES DONNEES {#5-migration-des-donnees}

### 5.1 Donnees joueur

Les proxys Minecraft stockent peu de donnees joueur directement. La majorite est dans les plugins :

| Type de donnee | Stocke par | Migration |
|---|---|---|
| Permissions | LuckPerms (base de donnees externe) | Aucune migration necessaire -- meme BDD |
| Bans | LiteBans/LibertyBans (BDD externe) | Aucune migration necessaire |
| Economie | Plugins backend (pas proxy) | Aucune migration necessaire |
| Skins | SkinsRestorer (BDD externe) | Aucune migration necessaire |
| Stats joueur | Plugins backend (pas proxy) | Aucune migration necessaire |
| Positions de reconnexion | Proxy (fichier/BDD) | Outil de migration a prevoir |

**Constat** : La grande majorite des donnees joueur sont stockees soit par les plugins backend (hors scope proxy), soit par des plugins proxy utilisant des bases de donnees externes (MySQL, Redis, SQLite). Le changement de proxy n'affecte pas ces donnees.

**Exception** : Le fichier de reconnexion de BungeeCord (`locations.yml`) qui mappe chaque UUID au dernier serveur visite. Warp doit pouvoir importer ce fichier.

### 5.2 Etat Redis (RedisBungee / ValioBungee)

Les reseaux multi-proxy utilisent Redis pour synchroniser l'etat :

| Cle Redis | Contenu | Migration |
|---|---|---|
| `player:<uuid>:server` | Serveur actuel du joueur | Ephemere, pas de migration |
| `player:<uuid>:proxy` | Proxy actuel | Ephemere, pas de migration |
| `proxy:<id>:players` | Set des joueurs sur ce proxy | Ephemere, pas de migration |
| `proxy:<id>:heartbeat` | Timestamp du dernier heartbeat | Ephemere, pas de migration |

**Constat** : Les donnees Redis de RedisBungee/ValioBungee sont TOUTES ephemeres (TTL courts, regenerees a chaque connexion). Aucune migration de donnees Redis n'est necessaire.

**Recommendation** : Si Warp supporte le multi-instance (roadmap future), il doit pouvoir coexister avec des instances Velocity dans le meme pool Redis pendant la migration. Format de cles compatible ou namespace different avec un convertisseur.

### 5.3 Schema de base de donnees

Warp ne devrait PAS avoir de base de donnees propre au MVP. Toute persistance est deleguee aux plugins. Cela elimine le probleme de migration de schema.

Si Warp a un jour besoin de persistance (reconnexion, statistiques internes), utiliser un format migrable (SQLite avec schema versionne + Flyway/Liquibase pattern).

---

## 6. PATTERNS DE MIGRATION GRADUELLE {#6-migration-graduelle}

### 6.1 Pattern recommande : Dual-Proxy avec Transfer Packet

Le Transfer Packet (1.20.5+) permet a un serveur de dire au client "connecte-toi a cette autre adresse". C'est la cle d'une migration zero-downtime.

#### Architecture

```
                    [DNS / HAProxy]
                    Round-robin ou weighted
                   /                    \
                  v                      v
            [Velocity]              [Warp]
            port 25565              port 25566
              |    |                  |    |
              v    v                  v    v
           [Lobby] [SkyWars]     [BedWars] [Hub-v2]
```

#### Scenario de migration pas a pas

**Etape 1 : Deploiement parallele**
- Installer Warp sur le meme serveur (ou un serveur dedie) avec un port different
- Configurer Warp avec les memes backends (ou un sous-ensemble)
- Verifier la connectivite Warp -> backends

**Etape 2 : Migration d'un server group test**
- Choisir un server group non-critique (ex: creative, minijeux peu frequentes)
- Configurer ce server group dans Warp uniquement
- Les joueurs qui rejoignent ce group passent par Warp

**Etape 3 : Dual-proxy avec Transfer Packet**
- Sur Velocity, installer un plugin qui utilise le Transfer Packet pour rediriger les joueurs vers Warp quand ils rejoignent un server group migre
- Le joueur ne voit rien (le client 1.20.5+ suit le transfer automatiquement)
- Rollback : desactiver le plugin de transfer = les joueurs restent sur Velocity

**Etape 4 : Migration progressive**
- Migrer un server group a la fois
- A chaque etape : monitorer les metriques, le feedback joueur, les erreurs
- Si probleme : rollback immediat (desactiver le transfer pour ce group)

**Etape 5 : Basculement DNS**
- Une fois tous les server groups migres et valides, basculer le DNS principal vers Warp
- Garder Velocity en standby pendant 1-2 semaines (rollback final)
- Retirer Velocity

#### Alternative sans Transfer Packet (clients < 1.20.5)

Pour les reseaux supportant des clients anciens :

**Option A : HAProxy weighted routing**
```
frontend minecraft
    bind *:25565
    default_backend velocity

backend velocity
    server velocity 127.0.0.1:25577 weight 90

backend warp
    server warp 127.0.0.1:25578 weight 10
```

Augmenter progressivement le poids de Warp : 10% -> 25% -> 50% -> 100%.

**Probleme** : Les joueurs sont assignes aleatoirement a un proxy, pas par server group. Moins de controle.

**Option B : DNS round-robin + sticky sessions**
- Deux records A/SRV pour le meme domaine
- Les joueurs se connectent aleatoirement a l'un des deux proxys
- Pas de controle fin, mais simple a mettre en place

**Option C : Migration big-bang (petits reseaux)**
- Arreter Velocity
- Lancer Warp
- Si probleme, rollback en re-lancant Velocity

Pour les reseaux < 100 joueurs concurrents, cette approche est souvent suffisante.

### 6.2 Basculement par pourcentage (modele Slack)

Pour les tres grands reseaux, suivre le modele de Slack :

| Phase | % sur Warp | Duree | Critere de passage |
|---|---|---|---|
| Canary | 1% | 1 semaine | Zero erreur critique |
| Early | 10% | 1 semaine | Metriques stables |
| Mid | 25% | 1 semaine | Performance >= Velocity |
| Late | 50% | 1 semaine | Aucun bug reporte |
| Majority | 75% | 3 jours | Confirmation finale |
| Complete | 100% | - | Velocity en standby 2 semaines |

### 6.3 Matrice de decision : Quel pattern pour quel reseau

| Taille du reseau | Pattern recommande | Complexite | Downtime |
|---|---|---|---|
| < 50 joueurs | Big-bang (arret/demarrage) | Faible | 5-15 minutes |
| 50-500 joueurs | Dual-proxy + Transfer Packet | Moyenne | Zero |
| 500-5000 joueurs | Dual-proxy + migration par server group | Haute | Zero |
| 5000+ joueurs | Basculement par pourcentage (modele Slack) | Tres haute | Zero |

---

## 7. GUIDE DE MIGRATION POUR LES DEVELOPPEURS DE PLUGINS {#7-migration-developpeurs}

### 7.1 Ce dont les developpeurs ont besoin

Base sur les retours de la migration BungeeCord -> Velocity :

1. **Tableau d'equivalence API** ("Rosetta Stone") : Chaque classe/methode BungeeCord/Velocity mappee a son equivalent Warp
2. **Exemples concrets** : Code avant/apres pour les patterns les plus courants
3. **Outil de detection** : "Scannez votre plugin pour voir quelles APIs migrer"
4. **Template de projet** : Gradle plugin Warp avec tous les defaults configures
5. **Testing framework** : Pouvoir tester le plugin SANS lancer un proxy complet

### 7.2 Rosetta Stone : Velocity API -> Warp API

**Structure du document** : Pour chaque concept, montrer le code Velocity et son equivalent Warp.

#### Exemple : Enregistrement d'un plugin

**Velocity** :
```java
@Plugin(id = "myplugin", name = "MyPlugin", version = "1.0",
        authors = {"Me"}, dependencies = {})
public class MyPlugin {
    private final ProxyServer server;
    private final Logger logger;

    @Inject // Guice
    public MyPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        // init
    }
}
```

**Warp** :
```java
@WarpPlugin(id = "myplugin", version = "1.0", authors = "Me")
public class MyPlugin {
    // Injection native, pas de Guice
    @Inject ProxyServer server;
    @Inject Logger logger;

    @OnEnable
    public void onEnable() {
        // init -- appele quand le plugin est pret
    }

    @OnDisable
    public void onDisable() {
        // cleanup -- garanti d'etre appele
    }
}
```

#### Exemple : Ecouter un evenement

**Velocity** :
```java
@Subscribe(order = PostOrder.NORMAL)
public void onLogin(LoginEvent event) {
    Player player = event.getPlayer();
    event.setResult(ResultedEvent.ComponentResult.allowed());
}

// Async (continuation-based)
@Subscribe
public EventTask onLogin(LoginEvent event) {
    return EventTask.async(() -> {
        // async work
    });
}
```

**Warp** :
```java
@EventHandler(priority = Priority.NORMAL)
public void onLogin(PlayerLoginEvent event) {
    Player player = event.player();
    // Autorise par defaut, pas besoin de setResult
}

// Async
@EventHandler(async = true)
public CompletableFuture<Void> onLogin(PlayerLoginEvent event) {
    return CompletableFuture.runAsync(() -> {
        // async work
    });
}
```

#### Exemple : Enregistrer une commande

**Velocity** :
```java
// Brigadier directement
LiteralCommandNode<CommandSource> node = LiteralArgumentBuilder
    .<CommandSource>literal("hub")
    .executes(ctx -> {
        if (ctx.getSource() instanceof Player player) {
            // ...
        }
        return Command.SINGLE_SUCCESS;
    })
    .build();
server.getCommandManager().register(new BrigadierCommand(node));
```

**Warp** :
```java
// Annotation-based (zero boilerplate)
@Command("hub")
@Permission("warp.command.hub")
public class HubCommand {
    @Inject ProxyServer server;

    @Default
    public void execute(Player player) {
        server.server("lobby").ifPresent(player::transfer);
    }
}
// Enregistrement automatique via annotation processing
```

### 7.3 Outil d'analyse de plugin

Outil en ligne de commande qui scanne un JAR de plugin Velocity et genere un rapport de migration :

```
$ warp plugin-analyzer my-velocity-plugin.jar

=== Analyse de migration : my-velocity-plugin v2.1 ===

Classes scannees : 42
Imports Velocity API : 28

APIs utilisees et equivalence Warp :
  [DIRECT]   ProxyServer.getAllPlayers()          -> ProxyServer.players()
  [DIRECT]   Player.sendMessage(Component)        -> Player.sendMessage(Component)
  [DIRECT]   Player.getCurrentServer()            -> Player.currentServer()
  [RENAME]   RegisteredServer.ping()              -> Server.ping()
  [CHANGE]   EventManager.register(plugin, this)  -> Automatique via @EventHandler
  [CHANGE]   BrigadierCommand(...)                -> @Command annotation
  [REMOVED]  player.getTabList().getEntries()     -> API TabList Warp (differente)

Score de migration :
  85% des APIs ont un equivalent direct
  10% necessitent un renommage simple
  5% necessitent une reecriture (TabList)

Effort estime : ~2 heures pour un developpeur familier avec Warp
```

### 7.4 Plugin Remapper (ambitieux, Phase 3)

Outil de transformation automatique de code source (AST-level) :

1. Parse le code source Java du plugin
2. Applique des regles de transformation (renommage, restructuration)
3. Genere le code Warp equivalent

**Scope realiste** : Ne transformer que les patterns simples (renommages, changements de signature). Les logiques complexes necessitent une intervention humaine.

**Inspiration** : `paper-remapper` (remap NMS -> Mojang mappings), `javaparser` pour l'AST.

**Priorite** : Phase 3 (nice-to-have). Le Rosetta Stone + plugin-analyzer couvrent 90% des besoins.

---

## 8. STRUCTURE DE LA DOCUMENTATION {#8-documentation}

### 8.1 Arborescence documentaire

```
docs/
  getting-started/
    installation.md
    quick-start.md
    migration/
      from-velocity.md          # Guide pas-a-pas Velocity -> Warp
      from-bungeecord.md        # Guide pas-a-pas BungeeCord -> Warp
      plugin-equivalences.md    # Tableau des equivalences plugins (top 50+)
      faq-migration.md          # Questions frequentes
      rollback.md               # Comment revenir en arriere

  admin-guide/
    configuration.md
    dual-proxy-setup.md         # Guide du mode dual-proxy
    transfer-packet-migration.md # Migration via Transfer Packet
    performance-tuning.md
    monitoring.md

  developer-guide/
    rosetta-stone.md            # Velocity API -> Warp API
    plugin-migration.md         # Guide complet migration de plugin
    api-reference.md
    examples/
      basic-plugin.md
      event-handling.md
      commands.md
      config.md
      testing.md

  case-studies/
    network-small.md            # Migration d'un reseau < 100 joueurs
    network-medium.md           # Migration d'un reseau 100-1000 joueurs
    network-large.md            # Migration d'un reseau 1000+ joueurs
```

### 8.2 Contenu prioritaire (Phase 0)

Les 5 pages a ecrire EN PREMIER, avant le lancement :

1. **`from-velocity.md`** : "Migrer de Velocity a Warp en 10 minutes"
   - Prereqs (Java 21+, backends Paper 1.20.5+)
   - Telecharger Warp
   - `warp migrate --from velocity /chemin/vers/velocity`
   - Verifier la configuration generee
   - Copier les plugins compatibles
   - Demarrer et tester

2. **`plugin-equivalences.md`** : Tableau complet des top 50 plugins
   - Nom BungeeCord | Nom Velocity | Nom Warp | Status | Notes

3. **`rosetta-stone.md`** : Les 20 patterns les plus utilises
   - Enregistrer un plugin
   - Ecouter un evenement
   - Enregistrer une commande
   - Envoyer un message
   - Transferer un joueur
   - Lire la configuration
   - Acceder aux serveurs
   - Plugin messaging
   - Scheduler (taches async)
   - Permissions

4. **`dual-proxy-setup.md`** : Comment faire tourner Warp a cote de Velocity

5. **`rollback.md`** : Comment revenir a Velocity si quelque chose tourne mal

---

## 9. STRATEGIE DE COMMUNICATION {#9-communication}

### 9.1 Positionnement

**NE PAS** :
- Se positionner comme "Velocity killer" ou "BungeeCord killer"
- Critiquer les mainteneurs de Velocity/BungeeCord (meme si les defauts techniques sont reels)
- Promettre la compatibilite totale des plugins

**POSITIONNER COMME** :
- "Le proxy de nouvelle generation pour les reseaux ambitieux"
- "Concu pour resoudre les problemes que Velocity ne peut pas corriger sans rewrite" (blind forwarding, drain natif, etc.)
- Completer l'ecosysteme PaperMC, pas le remplacer
- Remercier Velocity/BungeeCord pour avoir pave la voie

**Message cle** : "Warp n'existe pas parce que Velocity est mauvais. Warp existe parce que certains problemes ne peuvent etre resolus qu'avec une architecture fondamentalement differente."

### 9.2 Chronologie de communication

| Quand | Action | Canal | Ton |
|---|---|---|---|
| T-3 mois | Teaser technique : article sur le blind forwarding | Blog, r/admincraft | Technique, neutre |
| T-2 mois | Annonce open-source du repo (alpha) | GitHub, Discord MC dev | Humble, experimental |
| T-1 mois | Programme early adopter (5-10 reseaux) | Discord, invitations privees | Selectif |
| T-0 | Release beta publique | SpigotMC, Hangar, r/admincraft, PaperMC forums | Enthousiaste mais mesure |
| T+1 mois | Premiers case studies early adopters | Blog, Discord | Preuves concretes |
| T+3 mois | Release stable 1.0 | Tous canaux | Confiant |
| T+6 mois | "State of Warp" -- metriques, roadmap | Blog | Transparent |

### 9.3 Canaux de communication

| Canal | Usage | Frequence |
|---|---|---|
| **GitHub** | Code, issues, discussions techniques | Continu |
| **Discord** | Support migration, feedback, communaute | Continu |
| **r/admincraft** | Annonces majeures, case studies | Mensuel |
| **SpigotMC forums** | Annonces, discussions | Bimensuel |
| **PaperMC forums** | Discussions techniques, interop | Bimensuel |
| **Blog technique** | Articles de fond, benchmarks, architecture | Mensuel |
| **Hangar** | Distribution des plugins Warp | Continu |

### 9.4 Programme Early Adopter

**Criteres de selection** :
- Reseau de taille moyenne (100-1000 joueurs) -- assez gros pour etre representatif, assez petit pour un rollback facile
- Equipe technique competente (peut debugger, remonter des issues)
- Disposee a consacrer du temps aux rapports de bugs
- Mix de setups (vanilla, modde, minijeux, survival)

**Offre** :
- Support direct Discord avec les devs Warp
- Fixes prioritaires des bugs de migration
- Credit dans les case studies (si accord)
- Badge "Early Adopter" sur le Discord

**Engagement** :
- 4 semaines minimum de test en dual-proxy
- Rapport hebdomadaire (formulaire simple)
- Remonter tous les bugs sur GitHub

---

## 10. EVALUATION DES RISQUES {#10-risques}

### 10.1 Risques de migration et mitigations

| Risque | Probabilite | Impact | Mitigation |
|---|---|---|---|
| **Plugin critique sans equivalent Warp** | HAUTE | BLOQUANT | Bridge Velocity + liste d'equivalences maintenue + programme de portage |
| **Bug de forwarding causant des kicks** | MOYENNE | HAUTE | Tests exhaustifs modern forwarding. Mode fallback legacy |
| **Perte de connexion pendant le Transfer Packet** | FAIBLE | MOYENNE | Retry automatique cote client. Fallback DNS si echec |
| **Performance degradee vs Velocity** | FAIBLE | HAUTE | Benchmarks continus en CI. Regression = bloquant pour la release |
| **Incompatibilite avec un backend specifique** | MOYENNE | HAUTE | Matrice de compatibilite backends (Paper, Spigot, Fabric, Forge). Tests automatises |
| **Redis state corruption en dual-proxy** | FAIBLE | HAUTE | Namespaces Redis separes. Pas de partage d'etat entre Velocity et Warp |
| **Rollback echoue** | FAIBLE | CRITIQUE | Tester le rollback AVANT chaque etape de migration. Documentation rollback |
| **Reaction negative de la communaute Velocity** | MOYENNE | MOYENNE | Positionnement respectueux. Contributions upstream. Pas de FUD |

### 10.2 Scenarios de perte de donnees

| Scenario | Prevention |
|---|---|
| Fichier de reconnexion perdu | `warp migrate` copie le fichier. Backup automatique avant migration |
| BDD plugin corrompue | Les BDD plugins sont EXTERNES au proxy. Le changement de proxy n'y touche pas |
| Redis wipe accidentel | Donnees Redis ephemeres, regenerees a la reconnexion. Pas de perte permanente |
| Config Warp incorrecte generee | `warp migrate --check` (dry-run) avant la vraie migration. Validation de config au demarrage |

### 10.3 Scenarios de downtime

| Scenario | Duree max | Prevention |
|---|---|---|
| Bug critique en production | < 5 min (rollback) | Garder Velocity en standby. Rollback = relancer Velocity + re-pointer le DNS/HAProxy |
| Performance insuffisante | < 5 min (rollback) | Benchmarks avant mise en production. Monitoring en temps reel |
| Transfer Packet echoue | Invisible (fallback) | Les joueurs restent sur l'ancien proxy si le transfer echoue |
| Plugin crash au demarrage | < 2 min (fix config) | `warp migrate --check` detecte les problemes avant le lancement |

### 10.4 Checklist pre-migration

```
PRE-MIGRATION
[ ] Backup complet de l'installation Velocity/BungeeCord
[ ] `warp migrate --check` execute et rapport lu
[ ] Tous les plugins critiques ont un equivalent (natif ou bridge)
[ ] Backends testes avec Warp en environnement de dev
[ ] Forwarding mode teste et fonctionnel
[ ] Plan de rollback documente et teste
[ ] Monitoring en place (metriques, logs, alertes)
[ ] Equipe informee et disponible pendant la migration

MIGRATION
[ ] Warp demarre et accepte les connexions
[ ] Un joueur test peut se connecter et changer de serveur
[ ] Tous les plugins chargent sans erreur
[ ] Transfer Packet fonctionne (si utilise)
[ ] Metriques nominales (latence, CPU, memoire)

POST-MIGRATION
[ ] Monitoring stable pendant 24h
[ ] Aucun rapport joueur de bug
[ ] Performance >= Velocity
[ ] Rollback teste une derniere fois
[ ] Velocity retire (apres 1-2 semaines de stabilite)
```

---

## 11. CHRONOLOGIE {#11-chronologie}

### Timeline globale

```
PHASE 0 : PRE-MIGRATION (avant release publique)
├── Semaines 1-4 : Construire warp migrate CLI
├── Semaines 2-6 : Ecrire les 5 docs prioritaires
├── Semaines 3-6 : Construire le bridge Velocity (scope limite)
├── Semaine 4 : Compiler le tableau d'equivalence top 50 plugins
└── Semaine 6 : Test interne complet de migration

PHASE 1 : EARLY ADOPTERS (alpha/beta, 2-3 mois)
├── Semaine 7 : Recruter 5-10 early adopters
├── Semaines 7-10 : Support migration early adopters
├── Semaines 8-12 : Fix bugs remontes
├── Semaine 10 : Premiers case studies
└── Semaine 12 : Go/no-go pour beta publique

PHASE 2 : ADOPTION PROGRESSIVE (beta publique -> stable, 3-6 mois)
├── Semaine 13 : Release beta publique
├── Semaines 13-20 : Documentation complete
├── Semaines 13-24 : Stabilisation, performance
├── Semaine 20 : Plugin developer migration toolkit complet
└── Semaine 24 : Release stable 1.0

PHASE 3 : MIGRATION DE MASSE (6-12 mois post-stable)
├── Integration hosting providers
├── Programme "Warp Certified" plugins
├── Plugin remapper (optionnel)
├── Case studies grands reseaux
└── Deprecation du bridge Velocity (annonce a 1.0, retrait a 2.0)
```

---

## 12. DECISIONS ET RECOMMANDATIONS FINALES {#12-decisions}

### Decisions architecturales

| Decision | Choix | Justification |
|---|---|---|
| Compatibilite BungeeCord | **NON** | Trop eloigne architecturalement. Snap a prouve que c'est un puits sans fond |
| Bridge Velocity | **OUI, temporaire** | L'ecosysteme Velocity est le point de depart realiste. Bridge 80% de l'API, sunset en 12-18 mois |
| API propre | **OUI, prioritaire** | L'API doit etre NETTEMENT meilleure que Velocity pour justifier la migration. C'est le facteur d'adoption #1 pour les devs |
| Outil de migration config | **OUI, P0** | La premiere experience de migration doit etre < 10 minutes. L'outil CLI est indispensable |
| Migration zero-downtime | **OUI, via Transfer Packet** | Le Transfer Packet est la solution technique la plus elegante. Fallback HAProxy pour les clients anciens |
| Bridge BungeeCord | **NON** | Les lecons de Snap sont claires. Les plugins BungeeCord-only sont un marche en decline |
| Plugin remapper automatique | **Phase 3** | Nice-to-have. Le Rosetta Stone + analyzer couvrent 90% des besoins |

### Priorites de construction

| Priorite | Outil/Doc | Effort estime |
|---|---|---|
| **P0** | `warp migrate --from velocity` | 2-3 semaines |
| **P0** | `warp migrate --from bungeecord` | 1-2 semaines (plus simple, moins de features) |
| **P0** | Bridge Velocity (scope limite) | 4-6 semaines |
| **P0** | Doc "Migration en 10 minutes" | 1 semaine |
| **P0** | Tableau equivalence top 50 plugins | 2-3 jours |
| **P0** | Rosetta Stone (top 20 patterns) | 1 semaine |
| **P1** | `warp migrate --check` (dry-run) | 1 semaine |
| **P1** | Plugin analyzer (scan JAR) | 2 semaines |
| **P1** | Doc dual-proxy setup | 2-3 jours |
| **P1** | Benchmark comparatif automatise | 1-2 semaines |
| **P2** | Plugin remapper (AST transform) | 4-6 semaines |
| **P2** | Programme "Warp Certified" | Processus continu |

### Metriques de succes

| Metrique | Cible Phase 1 | Cible Phase 2 | Cible Phase 3 |
|---|---|---|---|
| Reseaux migres avec succes | 5-10 | 50-100 | 500+ |
| Temps moyen de migration | < 2h | < 30 min | < 10 min |
| Plugins natifs Warp disponibles | 10 | 30 | 100+ |
| % plugins utilisant le bridge | 80% | 40% | < 10% |
| Rollbacks effectues | Accepte | < 5% | < 1% |
| Bugs migration remontes/semaine | < 10 | < 3 | < 1 |

### Le point le plus important

> **La migration est un PRODUIT, pas une feature.** Elle merite le meme soin que le proxy lui-meme. Le meilleur proxy du monde est inutile si personne ne peut y migrer. Chaque heure investie dans les outils de migration a un ROI plus eleve que chaque heure investie dans une feature supplementaire du proxy.

Warp a un avantage que Velocity n'avait pas lors de son lancement : le precedent. On sait ce qui a marche (Paper : compatibilite totale), ce qui a partiellement marche (Velocity : rupture nette mais gains suffisants), et ce qui a echoue (Snap : compatibilite partielle bancale). Warp peut apprendre de ces trois cas et proposer la meilleure experience de migration de l'ecosysteme MC.
