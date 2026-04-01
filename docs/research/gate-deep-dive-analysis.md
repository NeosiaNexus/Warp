# Analyse approfondie de Gate (minekube/gate) -- Innovations a surpasser

**Date** : 31 mars 2026
**Sources** : Code source Go (github.com/minekube/gate@master), documentation officielle, issues GitHub, PR #113, Connect docs
**Objectif** : Identifier les innovations de Gate pour les surpasser dans Warp

---

## TABLE DES MATIERES

1. [Gate Lite -- Reverse Proxy Ultra-leger](#1-gate-lite-mode)
2. [Cache de Ping / Status](#2-cache-de-ping)
3. [Routage Wildcard par Hostname](#3-routage-wildcard)
4. [Load Balancing](#4-load-balancing)
5. [Gate Connect -- Reseau Edge](#5-gate-connect)
6. [Integration Geyser/Bedrock](#6-geyser-bedrock)
7. [Systeme de Plugins Go](#7-systeme-de-plugins)
8. [Faiblesses de Gate](#8-faiblesses)
9. [Synthese : Plan d'action Warp](#9-synthese)

---

## 1. GATE LITE MODE

### Comment ca fonctionne

Gate Lite est un mode "ultra-thin reverse proxy" qui route les connexions **sans deserialiser les paquets** apres le Handshake initial. C'est l'innovation la plus significative de Gate.

**Fichiers cles :**
- `pkg/edition/java/lite/forward.go` -- Coeur du forwarding
- `pkg/edition/java/lite/match.go` -- Matching de routes par hostname
- `pkg/edition/java/lite/strategy.go` -- Load balancing
- `pkg/edition/java/lite/config/config.go` -- Configuration
- `pkg/edition/java/lite/util.go` -- Utilitaires (virtualhost, TCPShield)

### Architecture du forwarding

Le flux est le suivant :

```
Client TCP --> Gate Lite --> Lit UNIQUEMENT le Handshake --> Extrait le hostname
  --> Match une route --> Dial TCP vers backend --> Ecrit le Handshake (modifie ou non)
  --> io.Copy bidirectionnel (pipe) --> Connexion opaque client <-> backend
```

**Code cle -- `Forward()` :**
```go
func Forward(dialTimeout time.Duration, routes []config.Route, log logr.Logger,
    client netmc.MinecraftConn, handshake *packet.Handshake,
    pc *proto.PacketContext, strategyManager *StrategyManager) {
    defer func() { _ = client.Close() }()
    // 1. findRoute() -- match hostname dans les routes
    // 2. tryBackends() -- essaie les backends dans l'ordre de la strategie
    // 3. emptyReadBuff() -- vide le buffer de lecture du client
    // 4. IncrementConnection() -- tracking pour least-connections
    // 5. pipe(log, src, dst) -- relay bidirectionnel
}
```

**Code cle -- `pipe()` :**
```go
func pipe(log logr.Logger, src, dst net.Conn) {
    var zero time.Time
    _ = src.SetDeadline(zero)   // Supprime les deadlines
    _ = dst.SetDeadline(zero)
    go func() {
        i, err := io.Copy(src, dst)  // goroutine: backend -> client
    }()
    i, err := io.Copy(dst, src)      // main: client -> backend
}
```

### Ce qui est malin

1. **Zero deserialisation post-handshake** : Apres avoir lu le Handshake, Gate Lite ne decode plus AUCUN paquet. Tout passe en `io.Copy` brut = copie kernel-level de bytes.
2. **Couche 7 minimale** : Gate verifie que la connexion parle le protocole MC (lecture du Handshake) avant de forwarder. Ca bloque le trafic non-MC sans etre un full parser.
3. **Modification du VirtualHost** : Option `modifyVirtualHost: true` reecrit le champ `ServerAddress` du Handshake avant forwarding, transparent pour le backend.
4. **emptyReadBuff()** : Vide les bytes deja bufferises par le reader avant de lancer le pipe. Evite la perte de donnees lors de la transition.
5. **Support Proxy Protocol + TCPShield** : Preservation de la vraie IP du client a travers les couches de proxy.

### Comment Warp fait MIEUX

| Aspect | Gate Lite | Warp (objectif) |
|--------|-----------|-----------------|
| **Forwarding** | `io.Copy` Go (userspace) | `splice(2)` ou `sendfile(2)` via `FileChannel.transferTo()` = zero-copy kernel. Java 21 offre ca via NIO |
| **Handshake** | Deserialise le paquet complet avec le codec Gate | Parse minimal : lire le VarInt length + le packet ID 0x00 + les champs VarInt/String strictement necessaires. Pas de codec complet |
| **Mode hybride** | Lite OU Full, pas les deux | **Mode unique** : blind forwarding par defaut, deserialisation selective uniquement pour les paquets enregistres par un plugin. Pas 2 modes a configurer |
| **Hot reload** | Doit relire le config | Routes dynamiques via API, hot-reloadable a chaud via CAS atomique sur la table de routes |
| **Metriques** | Aucune metrique integree | Compteurs Prometheus/Micrometer natifs : bytes transferes, connexions actives, latence par route |
| **Thread model** | 2 goroutines par connexion (pipe) | Virtual Threads Java 21 + NIO. Chaque connexion = 1 virtual thread avec `transferTo` non-bloquant |

**Pattern concret pour Warp :**
```java
// Zero-copy forwarding avec splice via Java NIO
public void pipe(SocketChannel client, SocketChannel backend) {
    // Java 21 virtual thread - blocage leger
    Thread.startVirtualThread(() -> {
        try {
            client.transferTo(0, Long.MAX_VALUE, backend); // kernel zero-copy
        } catch (IOException e) { /* cleanup */ }
    });
    backend.transferTo(0, Long.MAX_VALUE, client);
}
```

---

## 2. CACHE DE PING

### Comment Gate implemente le cache

**Architecture a double couche :**

1. **TTL Cache** : `ttlcache.New[pingKey, *pingResult]()` -- stocke les reponses status par `{backendAddr, protocol}`
2. **Singleflight** : `singleflight.Group` -- empeche les requetes concurrentes identiques (thundering herd prevention)

**Flux de resolution :**
```
Client ping --> findRoute() --> Cache hit? --> Oui : retourne immediatement
                                  |
                                  Non --> Singleflight lock sur la cle
                                           |
                                  Premier appel --> dialRoute() + fetchStatus()
                                                   --> Stocke en cache avec TTL
                                                   --> Retourne a tous les waiters
                                  Appels concurrents --> Attendent le premier
```

**Code cle -- `ResolveStatusResponse()` :**
```go
// Fast path : cache hit sans lock
// Slow path : singleflight.Do(key, func() {
//     dial backend, fetch status, store in cache with TTL
// })
// Fallback : si tous les backends echouent, retourne la reponse fallback configuree
```

**Configuration par route :**
```yaml
routes:
  - host: play.example.com
    backend: backend1:25565
    cachePingTTL: 60s      # 1 minute de cache
  - host: lobby.example.com
    backend: lobby:25565
    cachePingTTL: -1s       # Cache desactive
```

### Ce qui est malin

1. **Singleflight** : Emprunte au pattern Cloudflare. 10 000 pings simultanes = 1 seule connexion TCP au backend. Zero thundering herd.
2. **TTL par route** : Chaque route a son propre TTL. Un lobby peut avoir 10s, un serveur de build 5min.
3. **Fallback configurable** : Si tous les backends sont morts, Gate retourne un MOTD/favicon/version custom au lieu d'une erreur.
4. **Mesure de latence integree** : Le fetch de status mesure la latence et alimente la strategie `lowest-latency`.

### Comment Warp fait MIEUX

| Aspect | Gate | Warp (objectif) |
|--------|------|-----------------|
| **Invalidation** | TTL pure, pas de push-invalidation | TTL + invalidation evenementielle : quand un backend change d'etat (via health check), le cache est invalide immediatement |
| **Cache hierarchique** | Cache plat (un seul niveau) | Cache L1 (in-process Caffeine) + L2 optionnel (Redis/shared) pour les deployments multi-instance |
| **Personnalisation** | Le status retourne est celui du backend | Event `PingEvent` modifiable meme en mode blind-forward. Permet de fusionner les player counts de N backends |
| **Refresh asynchrone** | Le premier client apres expiration attend | **Stale-while-revalidate** : retourne l'ancienne valeur instantanement + lance le refresh en arriere-plan |
| **Compression** | Serialise le JSON a chaque reponse | Pre-serialise et pre-compresse le paquet Status une seule fois au refresh, retourne les bytes bruts a chaque client |

**Pattern concret pour Warp :**
```java
// Caffeine cache avec refresh async + singleflight via CompletableFuture
LoadingCache<PingKey, byte[]> pingCache = Caffeine.newBuilder()
    .refreshAfterWrite(Duration.ofSeconds(30))  // stale-while-revalidate
    .expireAfterWrite(Duration.ofMinutes(5))    // hard expiry
    .buildAsync(key -> fetchAndPreSerialize(key))
    .synchronous();

// Le pre-serialized byte[] est envoye directement au client sans re-JSON
```

---

## 3. ROUTAGE WILDCARD PAR HOSTNAME

### Extraction du hostname

Gate extrait le hostname depuis le paquet **Handshake** (packet ID `0x00` en state Handshake) :

```
Handshake packet:
  VarInt protocolVersion
  String serverAddress     <-- C'est ici que le hostname est
  UShort serverPort
  VarInt nextState          (1=Status, 2=Login)
```

Le champ `serverAddress` contient le hostname tel que tape par le joueur dans le client MC (ex: `play.example.com`).

**Nettoyage du hostname (`ClearVirtualHost`)** :
```go
// Supprime les separateurs Forge (\x00...) et TCPShield (///...)
// Puis trim les points en debut/fin
func ClearVirtualHost(name string) string {
    // Split sur forgeSeparator ("\x00"), garde la premiere partie
    // Split sur tcpShieldRealIPSeparator ("///"), garde la premiere partie
    // Trim les points
}
```

### Algorithme de matching

Gate convertit les patterns glob en regex, avec cache TTL d'1 heure :

```go
var compiledRegexCache = ttlcache.New[string, *regexp.Regexp](
    ttlcache.WithLoader(func(c *ttlcache.Cache, pattern string) *ttlcache.Item {
        regexStr := regexp.QuoteMeta(pattern)            // Echappe les metacaracteres
        regexStr = "^" + strings.ReplaceAll(regexStr, "\\?", "(.)") + "$"   // ? = un char
        regexStr = strings.ReplaceAll(regexStr, "\\*", "(.*?)")              // * = N chars
        reg, _ := regexp.Compile(regexStr)
        return c.Set(pattern, reg, time.Hour)
    }),
)
```

**Matching case-insensitive :**
```go
func match(s, pattern string) bool {
    reg := getRegexp(pattern)
    return reg != nil && reg.MatchString(strings.ToLower(s))
}

func matchWithGroups(s, pattern string) (bool, []string) {
    // ... FindStringSubmatch() pour capturer les groupes
    // matches[1:] = les groupes de capture ($1, $2, ...)
}
```

**Substitution de parametres dans le backend :**
```
Pattern: *.domain.com
Backend: $1.servers.svc:25565

Joueur se connecte a "lobby.domain.com"
  -> $1 = "lobby"
  -> Backend resolve = "lobby.servers.svc:25565"
```

### Ce qui est malin

1. **Capture groups parametriques** : Le `$1`, `$2` permet de construire dynamiquement l'adresse backend depuis le hostname. Ideal pour Kubernetes (service discovery via DNS).
2. **Cache de regex compiles** : Evite la recompilation a chaque connexion.
3. **Ordre de declaration** : Les routes sont matchees dans l'ordre du fichier, premiere qui matche gagne. Simple et previsible.
4. **Catchall** : `host: '*'` comme derniere route pour les connexions non matchees.

### Comment Warp fait MIEUX

| Aspect | Gate | Warp (objectif) |
|--------|------|-----------------|
| **Performance matching** | Regex compilee (lent pour des milliers de routes) | **Trie/radix tree** pour les routes exactes + regex uniquement pour les wildcards. O(log n) vs O(n) |
| **Routes dynamiques** | Fichier YAML statique, reload complet | API REST/gRPC pour CRUD de routes a chaud. Chaque modification est atomic (CAS) |
| **Validation** | Au demarrage uniquement | Validation en temps reel avec dry-run avant application |
| **Observabilite** | Aucune | Compteur de hits par route, latence p50/p99 par route, alertes sur routes sans trafic |
| **Templates avances** | `$1`, `$2` basiques | Expressions completes : `${1:lowercase}`, `${srv:-default}`, lookup DNS SRV, poids |
| **Multi-critere** | Hostname uniquement | Hostname + port source + protocole version + metadata custom |

**Pattern concret pour Warp :**
```java
// Trie-based router avec fallback regex
public class RouteTable {
    private final RadixTree<Route> exactRoutes = new RadixTree<>();
    private final List<WildcardRoute> wildcardRoutes = new CopyOnWriteArrayList<>();

    public Route resolve(String hostname) {
        // Fast path: exact match O(log n) dans le trie
        Route exact = exactRoutes.get(hostname.toLowerCase());
        if (exact != null) return exact;

        // Slow path: wildcard matching (rarement atteint si bien configure)
        for (WildcardRoute wr : wildcardRoutes) {
            if (wr.matches(hostname)) return wr.resolve(hostname);
        }
        return defaultRoute;
    }
}
```

---

## 4. LOAD BALANCING

### Les 5 strategies de Gate

**`StrategyManager` struct :**
```go
type StrategyManager struct {
    rng                *rand.Rand
    roundRobinIndexes  *sync.Map            // map[routeHost]int
    connectionCounters *sync.Map            // map[backend]*atomic.Uint32
    latencyCache       *ttlcache.Cache[string, time.Duration]  // TTL 3 min
}
```

#### Sequential (defaut)
Retourne le premier backend de la liste. Les backends en echec sont retires de la copie locale. Simple failover.

#### Random
`rng.Intn(len(backends))` -- selection aleatoire avec CSPRNG. Pas de tracking d'etat.

#### Round-Robin
```go
// Index per-routeHost, increment + modulo
index := sm.getOrCreateIndex(routeHost)
selected := backends[index % len(backends)]
sm.setIndex(routeHost, index + 1)
```

#### Least-Connections
```go
func (sm *StrategyManager) IncrementConnection(backend string) func() {
    counter := sm.getOrCreateCounter(backend)
    counter.Add(1)
    return func() { counter.Add(^uint32(0)) }  // Decrement atomique
}
// Selection : itere tous les backends, prend celui avec counter.Load() minimum
```

#### Lowest-Latency
```go
func (sm *StrategyManager) RecordLatency(backend string, latency time.Duration) {
    sm.latencyCache.Set(backend, latency, time.Minute*3)
}
// Selection : itere le cache, prend la latence minimum
// Backends non mesures = priorite haute (pour l'evaluation initiale)
```

### Ce qui est malin

1. **Atomic counters** pour least-connections : zero lock, zero contention.
2. **Decrement via closure** : `IncrementConnection` retourne un `func()` de cleanup. Pattern Go elegant.
3. **Latence mesuree a partir du ping cache** : pas de health check separe, la resolution de status fait double emploi.
4. **Backends non mesures privilegies** : les nouveaux backends sont testes en priorite.

### Comment Warp fait MIEUX

| Aspect | Gate | Warp (objectif) |
|--------|------|-----------------|
| **Health checks** | Aucun (connection failure = retry suivant) | Health checks actifs periodiques + circuit breaker par backend |
| **Weighted round-robin** | Non supporte | Poids configurable par backend (ex: gros serveur = poids 3) |
| **Consistent hashing** | Absent | Pour les cas ou le meme joueur doit toujours aller sur le meme shard |
| **Slow-start** | Absent | Rampe progressive sur les backends fraichement ajoutes (evite les thundering herds) |
| **Metriques** | Compteurs internes uniquement | Export Prometheus : connexions actives, latence, taux d'erreur par backend |
| **Drain-aware** | Non | Un backend en drain est retire du pool de selection mais garde ses connexions existantes |

**Pattern concret pour Warp :**
```java
// Circuit breaker + weighted selection
public class SmartBalancer implements LoadBalancer {
    private final AtomicInteger roundRobinCounter = new AtomicInteger();
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public Backend select(List<WeightedBackend> backends) {
        List<WeightedBackend> healthy = backends.stream()
            .filter(b -> breakers.get(b.address()).isOpen() == false)
            .filter(b -> b.drainingState() == false)
            .toList();

        if (healthy.isEmpty()) throw new NoHealthyBackendException();

        // Weighted round-robin
        int totalWeight = healthy.stream().mapToInt(WeightedBackend::weight).sum();
        int idx = roundRobinCounter.getAndIncrement() % totalWeight;
        // ... selection par accumulation de poids
    }
}
```

---

## 5. GATE CONNECT -- RESEAU EDGE

### Architecture

Gate Connect est un **reseau edge type Cloudflare pour Minecraft**. Architecture :

```
Joueur --> Connect Edge (datacenter proche) --> Tunnel gRPC --> Connector (sur le serveur MC)
                                                                --> Backend MC local
```

**Composants :**
- **Edge Proxies** : Serveurs publics dans des datacenters mondiaux
- **Connectors** : Composants logiciels sur les serveurs MC, etablissent des tunnels **sortants** vers les edges
- **Session Service** : Coordonne les sessions entre edges et connectors

**Protocole :** gRPC avec metadata pour l'identification de session. Le tunnel est un flux gRPC bidirectionnel.

**Flux de connexion :**
1. Le joueur se connecte a l'edge le plus proche
2. L'edge envoie une "session proposal" au Connector du serveur cible
3. Le Connector accepte et etablit un tunnel vers l'edge du joueur
4. Le trafic MC transite dans le tunnel gRPC

**Code cle (`tunnel.go`)** :
```go
// Validation de session via metadata gRPC
func RequireTunnelSessionID(listener TunnelListener) TunnelListener {
    // Verifie la presence du session ID dans les metadata gRPC
    // Rejette les tunnels sans session valide
}
```

**Code cle (`connect.go`)** :
```go
func setupConnect(/* ... */) {
    // Hash de config pour eviter les redemarrages inutiles
    // Souscrit aux reload events
    // Cree/detruit les instances Connect dynamiquement
}
```

### Ce qui est malin

1. **Tunnels sortants** : Pas besoin de port forwarding. Le serveur MC initie la connexion vers l'edge. Traverse NAT et firewalls.
2. **Session proposals** : L'edge ne connecte pas directement au backend. Il propose une session, le connector decide d'accepter ou non.
3. **gRPC** : Multiplexing HTTP/2, TLS natif, metadata pour le routage.
4. **Latence minimale** : Le joueur se connecte au datacenter le plus proche, le tunnel interne utilise le reseau du fournisseur cloud.

### Ce que Warp peut apprendre (sans copier le modele SaaS)

| Concept | Application pour Warp |
|---------|----------------------|
| **Tunnel reverse** | Offrir un module `warp-tunnel` optionnel pour exposer des serveurs derriere NAT |
| **Session proposals** | Pattern utile pour le drain : proposer la session a un autre proxy avant de couper |
| **gRPC bidirectionnel** | Pour l'API de controle inter-proxys (cluster mode) |
| **Config hot-reload** | Le pattern hash-based reload de Gate est simple et robuste |

---

## 6. INTEGRATION GEYSER/BEDROCK

### Architecture

Gate utilise une approche **proxy-devant-proxy** avec Geyser integre :

```
Joueur Bedrock (UDP:19132) --> Geyser Standalone (process Java separe)
    --> Traduction Bedrock -> Java --> Gate (TCP:25565)
    --> Floodgate auth native --> Backend Java (voit un joueur Java normal)
```

**Fichiers cles :**
- `pkg/edition/bedrock/geyser/managed/managed.go` -- Lifecycle Geyser
- `pkg/edition/bedrock/geyser/floodgate/floodgate.go` -- Protocol Floodgate natif
- `pkg/edition/bedrock/geyser/floodgate/cipher.go` -- AES-128 encryption

### Geyser Managed Mode

Gate gere **automatiquement** le lifecycle de Geyser Standalone :

1. **Telechargement** : Download du JAR avec ETag / `If-Modified-Since` pour les mises a jour
2. **Configuration** : Generation dynamique du `config.yml` Geyser (auth-type: floodgate, proxy-protocol: true)
3. **Lancement** : Spawn du process Java, capture stdout/stderr avec prefixe `[GEYSER]`
4. **Detection de readiness** : Surveille la sortie pour `"Done ("` (timeout 30s)
5. **Arret** : Kill + Wait avec mutex de protection

### Floodgate natif

Gate implemente le protocole Floodgate **directement en Go**, sans plugin backend :

```go
// Le hostname contient les donnees chiffrees
// Format: "original_hostname\x00encrypted_data"
func (f *Floodgate) ReadHostname(hostname string) (*BedrockData, string, error) {
    parts := strings.SplitN(hostname, "\x00", 2)
    encrypted := parts[1]
    decrypted := f.cipher.Decrypt(encrypted)
    return parseBedrockData(decrypted)  // 12 champs separes par \x00
}
```

**Donnees extraites :** Version, Username, XUID, Device OS, Language, UI Profile, Input Mode, IP, etc.

**Generation UUID deterministe :**
```go
func JavaUuid(xuid int64) uuid.UUID {
    // SHA-1 de "FloodgateXUID:" + xuid
    // RFC 4122 v5 UUID
}
```

### Ce qui est malin

1. **Zero plugin** : Floodgate est natif dans Gate, pas besoin de l'installer sur chaque backend.
2. **Geyser managed** : Un seul `bedrock: managed: true` et tout fonctionne.
3. **UUID deterministe** : Le meme joueur Bedrock a toujours le meme UUID Java.
4. **Telechargement conditionnel** : ETags pour ne pas retelecharger Geyser a chaque restart.

### Comment Warp fait MIEUX

| Aspect | Gate | Warp (objectif) |
|--------|------|-----------------|
| **Architecture** | Process Java separe (Geyser Standalone) | Option d'integrer Geyser comme module interne (meme JVM), eliminant le hop reseau |
| **Protocol** | Floodgate via hostname hack (champ ServerAddress) | Implemente aussi le standard, mais offre une alternative via Login Plugin Message plus propre |
| **Performance** | 2 process, serialisation entre eux | Meme JVM = zero serialisation, passage direct d'objets |
| **Monitoring** | Parse du stdout de Geyser | Si meme JVM : health checks internes, metriques Bedrock natives |
| **Fallback** | Pas de Lite mode pour Bedrock | Support Bedrock en mode Lite aussi (si techniquement faisable) |

---

## 7. SYSTEME DE PLUGINS (Go)

### Architecture

Gate utilise un systeme de plugins **compile-time** (pas de chargement dynamique) :

```go
// Registration globale
var Plugins []Plugin

type Plugin struct {
    Name string
    Init func(ctx context.Context, proxy *Proxy) error
}

// Usage
proxy.Plugins = append(proxy.Plugins, proxy.Plugin{
    Name: "MonPlugin",
    Init: func(ctx context.Context, p *proxy.Proxy) error {
        event.Subscribe(p.Event(), 0, func(e *proxy.PreLoginEvent) {
            // ...
        })
        return nil
    },
})
```

**Lifecycle :**
1. Les plugins sont enregistres dans le slice `Plugins`
2. Au demarrage, chaque `Init` est appele avec context + proxy
3. Si Init retourne une erreur, le proxy s'arrete
4. A l'arret, `ShutdownEvent` est fire pour le cleanup
5. Le context est cancel pour signaler l'arret

### Systeme d'evenements

**30+ types d'evenements** couvrant tout le lifecycle :

| Phase | Evenements |
|-------|-----------|
| **Connexion** | ConnectionEvent, ConnectionHandshakeEvent, PingEvent |
| **Authentification** | PreLoginEvent, GameProfileRequestEvent, LoginEvent, PostLoginEvent |
| **Serveur** | ServerPreConnectEvent, ServerConnectedEvent, ServerPostConnectEvent, KickedFromServerEvent |
| **Jeu** | PlayerChatEvent, CommandExecuteEvent, TabCompleteEvent, PlayerSettingsChangedEvent |
| **Plugins** | PluginMessageEvent, PlayerChannelRegisterEvent/Unregister |
| **Resources** | PlayerResourcePackStatusEvent, ServerResourcePackSendEvent |
| **Cookies** | CookieReceiveEvent, CookieStoreEvent, CookieRequestEvent |
| **Lifecycle** | ReadyEvent, PreShutdownEvent, ShutdownEvent, DisconnectEvent |

**Pattern de souscription :**
```go
event.Subscribe(proxy.Event(), priority, func(e *proxy.PreLoginEvent) {
    e.Deny(&component.Text{Content: "Acces refuse"})
    // ou e.Allow(), e.ForceOnlineMode(), e.ForceOfflineMode()
})
```

**Resultats modifiables :**
```go
// KickedFromServerEvent
e.SetResult(&DisconnectPlayerKickResult{Reason: ...})
e.SetResult(&RedirectPlayerKickResult{Server: target, Message: ...})
e.SetResult(&NotifyKickResult{Message: ...})
```

### Ce qui est malin

1. **Type-safe events** : Chaque event est un type Go distinct. Pas de casting.
2. **Result patterns** : Les events de decision (kick, login) ont des types result qui contraignent les reponses possibles.
3. **Priorites** : Les handlers s'executent dans l'ordre de priorite.
4. **Simplicite** : Pas de Go plugin system natif (juge trop fragile), mais compilation directe.

### Faiblesses du modele Go

1. **Pas de hot-reload** : Chaque changement de plugin = recompilation + restart complet.
2. **Ecosysteme limite** : Tres peu de plugins communautaires (vs centaines pour Velocity/BungeeCord).
3. **Pas de marketplace** : Aucun equivalent a SpigotMC/Modrinth/Hangar.
4. **Courbe d'apprentissage** : Les admins MC connaissent Java, pas Go.

### Comment Warp fait MIEUX

| Aspect | Gate | Warp (objectif) |
|--------|------|-----------------|
| **Chargement** | Compile-time uniquement | Hot-reload de plugins JAR a chaud (classloader isole) |
| **Ecosysteme** | Quasi inexistant | Compatible avec l'ecosysteme Java MC existant. Bridge Velocity Event API optionnel |
| **DX** | Un seul binaire Go | SDK Maven/Gradle, templates de projet, Javadoc complete |
| **Isolation** | Aucune (meme process) | Classloaders isoles, permissions par plugin, resource limits |
| **Events** | Type-safe mais basique | Type-safe + order garanti + timeout + async natif + thread-safety par design |
| **API** | Go structs (compile-time) | API stable avec @ApiStatus.Stable / @ApiStatus.Experimental, versioning semantique |

---

## 8. FAIBLESSES DE GATE

### Issues ouvertes critiques

#### Bug #587 : NeoForge Login Disconnect (ATM10sky)
- **Probleme** : Deconnexion immediate avec les gros modpacks NeoForge. Le decompresseur de Gate ne lisait pas les paquets complets (EOF premature sur les `LoginPluginMessage` volumineux).
- **Cause racine** : `SetReader` ne wrappait pas le nouveau reader avec `fullReader`, causant des lectures incompletes avec `Read()` (qui peut retourner moins de bytes que demande).
- **Lecon pour Warp** : **Toujours utiliser `readFully()`** ou equivalent. Ne jamais assumer qu'un seul `read()` retourne tous les bytes demandes. C'est un piege classique en IO non-bloquante.

#### Bug #597 : Trident / SoundEntityPacket EOF
- **Probleme** : Lancer un trident deconnecte le joueur et les joueurs proches avec "error reading varint: EOF".
- **Cause racine** : Bug dans le decodeur de `SoundEntityPacket`. Lit des bytes incorrects, desynchronise le flux pour tous les paquets suivants.
- **Lecon pour Warp** : En mode blind forwarding, ce bug **n'existe pas** car le proxy ne decode pas les paquets Play. C'est un argument de plus pour le blind forwarding par defaut.

#### Feature #187 : Kubernetes Service Discovery (ouvert depuis 2022)
- **Probleme** : Gate n'a pas de decouverte de service Kubernetes native. Les routes doivent etre configurees manuellement.
- **Lecon pour Warp** : Implementer un `ServiceDiscovery` pluggable : DNS SRV, K8s API, Consul, statique.

### Faiblesses architecturales

1. **Pas de mode hybride Lite/Full** : Impossible d'avoir du blind forwarding + interception selective. C'est l'un ou l'autre.
2. **Pas de drain natif** : Aucun mecanisme pour vider progressivement les joueurs d'une instance.
3. **Pas de metrics/observabilite** : Aucun export Prometheus, aucun tracing, aucun structured logging standardise.
4. **Modded servers fragiles** : Les bugs #587 et #597 montrent que le parsing de paquets est fragile pour les setups moddes.
5. **Plugin ecosystem inexistant** : ~0 plugins communautaires. Les serveurs MC ont besoin de LuckPerms, TAB, etc.
6. **Pas de compatibility layer** : Impossible de reutiliser les plugins Velocity/BungeeCord existants.
7. **Geyser = process separe** : Latence supplementaire vs integration dans la meme JVM.
8. **Aucun benchmark publie** : Gate clame 10MB RAM et "milliers de joueurs" sans aucune preuve chiffree.
9. **Documentation limitee** : Pas d'API reference complete, pas de guides avances.
10. **Connect = vendor lock-in** : Le reseau edge est un service SaaS proprietaire de Minekube, pas auto-hebergeable.

---

## 9. SYNTHESE : PLAN D'ACTION WARP

### Innovations a reprendre de Gate (ameliorees)

| # | Innovation Gate | Implementation Warp | Priorite |
|---|----------------|---------------------|----------|
| 1 | **Blind forwarding (Lite mode)** | Mode par defaut, pas un mode separe. `splice(2)` zero-copy. Deserialisation selective par plugin | **P0 -- Coeur** |
| 2 | **Ping cache + singleflight** | Caffeine + CompletableFuture singleflight. Stale-while-revalidate. Pre-serialisation | **P0 -- Coeur** |
| 3 | **Wildcard hostname routing** | Trie + regex fallback. Routes dynamiques via API. Templates avances | **P0 -- Coeur** |
| 4 | **Load balancing multi-strategie** | 5 strategies Gate + weighted RR + consistent hashing + circuit breaker + drain-aware | **P1 -- Important** |
| 5 | **Fallback status responses** | Status fallback configurable + event hook pour personnalisation dynamique | **P1 -- Important** |
| 6 | **Floodgate natif** | Implementer le protocole Floodgate directement dans Warp, sans plugin backend | **P2 -- Differenciateur** |
| 7 | **Geyser integration** | Option JVM integree (meme process) en plus du mode standalone | **P2 -- Differenciateur** |
| 8 | **Tunnel reverse (Connect-like)** | Module optionnel `warp-tunnel` pour les serveurs derriere NAT, auto-hebergeable | **P3 -- Future** |

### Avantages competitifs de Warp sur Gate

1. **Mode unique hybride** au lieu de Lite/Full -- le blind forwarding EST le mode normal
2. **Ecosysteme Java** -- plugins existants, developpeurs abondants, Maven Central
3. **Hot-reload de plugins** -- zero downtime pour les mises a jour de plugins
4. **Drain natif** -- ce que ni Velocity, ni BungeeCord, ni Gate n'offrent
5. **Observabilite native** -- Prometheus, OpenTelemetry, structured logging
6. **Kubernetes-native** -- service discovery, health probes, HPA ready
7. **Zero-copy kernel** -- `splice(2)` via Java NIO vs `io.Copy` userspace de Go
8. **Securite renforcee** -- rate limiting natif, circuit breakers, validation de paquets configurable
9. **Documentation et DX** -- Javadoc, SDK, templates, guides, marketplace a terme
10. **Pas de vendor lock-in** -- tout est open-source et auto-hebergeable
