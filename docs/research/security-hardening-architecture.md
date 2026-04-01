# Architecture de securite et resilience de Warp

**Date** : 31 mars 2026
**Objectif** : Faire de Warp le proxy Minecraft le plus sur du marche
**Sources** : CVE databases, GitHub Issues (Velocity, BungeeCord, Netty), Microsoft Security Blog, Envoy/HAProxy/NGINX docs, Resilience4j, Oracle Secure Coding Guidelines, LimboFilter, TCPShield, OWASP, recherche academique sur le fuzzing de protocoles

---

## CLASSIFICATION DES MESURES

| Tag | Signification |
|-----|---------------|
| **BUILT-IN** | Integre au coeur du proxy, toujours actif, non desactivable |
| **CONFIGURABLE** | Integre au coeur mais parametrable par l'administrateur |
| **PLUGIN** | Extension via l'API plugin, non inclus dans le core |

---

## TABLE DES MATIERES

1. [Vecteurs d'attaque connus](#1-vecteurs-dattaque-connus)
2. [Couche reseau (L3/L4)](#2-couche-reseau-l3l4)
3. [Couche protocole Minecraft (L7)](#3-couche-protocole-minecraft-l7)
4. [Anti-bot et anti-DDoS applicatif](#4-anti-bot-et-anti-ddos-applicatif)
5. [Forwarding et authentification](#5-forwarding-et-authentification)
6. [Resilience et tolerance aux pannes](#6-resilience-et-tolerance-aux-pannes)
7. [Securite Java](#7-securite-java)
8. [Observabilite securitaire](#8-observabilite-securitaire)
9. [Degradation gracieuse sous charge](#9-degradation-gracieuse-sous-charge)
10. [Matrice recapitulative](#10-matrice-recapitulative)

---

## 1. VECTEURS D'ATTAQUE CONNUS

### 1.1 Menaces documentees contre les proxys MC

| Vecteur | Description | Proxys affectes | Ref |
|---------|-------------|-----------------|-----|
| **Bombe de decompression** | Paquets compresses (39 KB) se decompressant en ~26.6 MB, causant OOM | Velocity (#1742), BungeeCord | Velocity #1742, Netty GHSA-3p8m |
| **Bombe NBT** | Structure NBT imbriquee : 300 listes x 10 sous-listes x 5 niveaux = 30M objets. Ratio compression 682:1 | Tous les proxys deserialisant les paquets | MC-79612, ammaraskar advisory |
| **Join spam (botnet)** | 100k+ connexions/sec via botnets (MCCrash, Aisuru: 6.35+ Tbps en 2025) | Tous | Microsoft Security Blog |
| **Ping flood** | Saturation via status requests repetees | BungeeCord (#2854 : crash a 200-300 req/s), Velocity | BungeeCord #2854 |
| **Slowloris MC** | Connexions ouvertes mais progression de handshake extremement lente pour epuiser les slots | Tous | Analogie HTTP Slowloris |
| **Confusion d'etat protocole** | Envoi de paquets d'un etat (LOGIN) pendant un autre (PLAY), causant deserialisations incorrectes | BungeeCord (#3519, #3542), Velocity (#1723) | Hypixel Forums |
| **Overflow VarInt** | VarInt encode sur plus de 5 octets (spec: max 5 bytes), causant des lectures infinies | Implementations naives | wiki.vg |
| **String length attack** | Chaines > 32767 caracteres dans les paquets, depassant les limites memoire | Implementations sans validation | node-minecraft-protocol #60 |
| **IP spoofing via forwarding** | Usurpation d'identite en injectant des headers BungeeCord en texte clair | BungeeCord (#1328, ouvert depuis 2015) | SpigotMC exploit fix |
| **Token leak BungeeGuard** | Build BungeeCord 1756+ fuit le token BungeeGuard aux joueurs 1.20.2+ via LoginSuccess | BungeeCord | BungeeGuard security |
| **BleedingPipe (deserialization RCE)** | ObjectInputStream dans les mods -> RCE via paquets malveillants | Mods utilisant la serialisation Java | Malwarebytes 2023 |
| **CVE-2023-30859 (Triton)** | Plugin BungeeCord: execution de commandes via CustomPayload en mode bungee | BungeeCord + plugin Triton | NVD |
| **Acces direct backend** | Bypass du proxy si les ports backend sont exposes (firewall mal configure) | Tous (BungeeHack) | MCPTool docs |
| **PROXY protocol spoofing** | Injection de headers PROXY protocol depuis des sources non-approuvees | Tout proxy acceptant PROXY protocol | APNIC Blog, HAProxy spec |

### 1.2 Botnets ciblent specifiquement Minecraft

- **MCCrash** (Microsoft, dec. 2022) : botnet cross-plateforme, se propage via SSH, envoie des commandes serveur craftees + payloads Log4j
- **Aisuru** (2025) : record a 6.35 Tbps puis 11+ Tbps, DDoS-as-a-Service
- **Outils publics** : GitHub regorge de projets `minecraft-ddos`, `minecraft-botnet` avec stressers cles-en-main
- **SoulFire** : framework de test qui simule des centaines de joueurs, utilisable pour les tests mais aussi comme outil d'attaque

---

## 2. COUCHE RESEAU (L3/L4)

### 2.1 Protection SYN flood -- CONFIGURABLE

**Probleme** : Un SYN flood epuise la file d'attente de connexions TCP du serveur.

**Implementation Warp** :
- Documenter les parametres kernel Linux recommandes :
  - `net.ipv4.tcp_syncookies = 1` (SYN cookies quand la queue est pleine)
  - `net.ipv4.tcp_max_syn_backlog = 8192` (taille de la SYN queue)
  - `net.ipv4.tcp_synack_retries = 2` (reduire les retransmissions)
- Configurer le `ServerBootstrap` Netty avec un backlog adapte :
  ```java
  .option(ChannelOption.SO_BACKLOG, 256) // defaut, configurable
  ```
- Fournir un guide de hardening OS dans la documentation

**Lecon Envoy** : Envoy documente explicitement les recommandations kernel, pas seulement l'application.

### 2.2 Rate limiting par IP au niveau connexion TCP -- CONFIGURABLE

**Probleme** : Une IP ouvre des centaines de connexions simultanees pour saturer le proxy.

**Implementation Warp** :
```
security:
  connection-rate-limit:
    enabled: true
    max-connections-per-ip: 3          # connexions simultanees
    max-new-connections-per-second: 5  # nouvelles connexions/sec/IP
    global-max-new-connections-per-second: 500  # global
    burst-allowance: 10                # pic temporaire autorise
    exempted-ips:                      # IPs de confiance
      - "10.0.0.0/8"
```

**Implementation technique** :
- Handler Netty `ConnectionThrottleHandler` insere au debut du pipeline
- Structure de donnees : `ConcurrentHashMap<InetAddress, ConnectionCounter>` avec compteurs atomiques
- Algorithme : Token Bucket par IP (comme Envoy local rate limiting)
- Les IPs exemptees (reseaux internes, load balancers) ne sont jamais limitees
- Nettoyage periodique des entrees (eviter les fuites memoire)

**Lecon NGINX** : le leaky bucket est plus stable que le token bucket pur sous charge constante. Proposer les deux algorithmes.

### 2.3 Timeouts agressifs -- CONFIGURABLE

**Probleme** : Les attaques Slowloris maintiennent des connexions semi-ouvertes indefiniment.

**Implementation Warp** :
```
security:
  timeouts:
    connect-timeout-ms: 5000     # timeout pour etablir la connexion TCP
    handshake-timeout-ms: 10000  # login complet doit finir en 10s
    read-timeout-ms: 30000       # timeout entre deux paquets (PLAY state)
    write-timeout-ms: 10000      # timeout d'ecriture
    configuration-phase-timeout-ms: 15000  # phase CONFIG doit finir
```

**Implementation technique** :
- `ReadTimeoutHandler` et `WriteTimeoutHandler` de Netty dans le pipeline
- Timeout specifique pour le SSL/TLS handshake (si supporte) : 2s
- Le handshake timeout couvre Handshake -> Login -> Configuration -> PLAY
- Tout timeout = deconnexion immediate + increment compteur

**Lecon HAProxy** : HAProxy distingue `timeout client`, `timeout server`, `timeout connect`, `timeout http-request`. Warp devrait faire de meme avec des timeouts par phase du protocole MC.

### 2.4 Limites de taille de payload -- BUILT-IN

**Probleme** : Paquets surdimensionnes = decompression couteuse ou buffer overflow.

**Implementation Warp** :
- **Limite paquet compresse** : 2 MB max (spec MC: 2^21 - 1 = 2,097,151 bytes)
- **Limite paquet decompresse** : 8 MB max (spec MC serverbound: 2^23 = 8,388,608 bytes)
- **Limite chaine** : 32767 caracteres max (spec MC)
- **Limite VarInt** : exactement 5 bytes max, exception immediatement si depasse
- **Limite VarLong** : exactement 10 bytes max
- Toute violation = deconnexion immediate + log WARNING

**Implementation technique** :
```java
// Dans le decodeur VarInt
if (bytesRead > 5) {
    throw new DecoderException("VarInt too long (>" + MAX_VARINT_BYTES + " bytes)");
}

// Dans le decodeur de paquets
if (uncompressedLength > MAX_UNCOMPRESSED_PACKET_SIZE) {
    throw new DecoderException("Packet too large: " + uncompressedLength
        + " > " + MAX_UNCOMPRESSED_PACKET_SIZE);
}
```

### 2.5 Backlog et configuration Netty -- CONFIGURABLE

**Recommandations pour le ServerBootstrap** :
```java
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(EpollServerSocketChannel.class)  // epoll sur Linux
 .option(ChannelOption.SO_BACKLOG, config.getBacklog())        // 256 defaut
 .option(ChannelOption.SO_REUSEADDR, true)
 .childOption(ChannelOption.SO_KEEPALIVE, true)
 .childOption(ChannelOption.TCP_NODELAY, true)
 .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
 .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
     new WriteBufferWaterMark(32 * 1024, 64 * 1024));  // backpressure
```

**Points cles** :
- Utiliser `epoll` (Linux) ou `kqueue` (macOS) au lieu de NIO quand disponible
- Write buffer water mark = mecanisme de backpressure Netty natif
- `PooledByteBufAllocator` obligatoire pour eviter les allocations repetees

### 2.6 PROXY Protocol -- securite stricte -- CONFIGURABLE

**Probleme** : Le PROXY protocol (v1/v2) transmet l'IP reelle du client via le header, mais un attaquant peut injecter un faux header s'il accede directement au proxy.

**Implementation Warp** :
```
proxy-protocol:
  enabled: false          # desactive par defaut
  trusted-proxies:        # OBLIGATOIRE si active
    - "103.21.244.0/22"   # Cloudflare
    - "10.0.0.1/32"       # HAProxy interne
  reject-untrusted: true  # connexion refusee si IP non-approuvee envoie PROXY header
```

**Regles de securite** :
- Si `proxy-protocol.enabled = false` : tout header PROXY protocol entrant est ignore (bytes lus et rejetes)
- Si `proxy-protocol.enabled = true` mais IP non dans `trusted-proxies` : deconnexion immediate
- **Jamais** de detection automatique PROXY/non-PROXY (source de confusion d'etat)
- Log WARNING pour toute tentative de PROXY protocol depuis une IP non approuvee

**Lecon Gate** : Gate a un systeme similaire avec whitelist d'IPs de confiance pour PROXY protocol. La documentation insiste sur le fait que c'est la premiere cause de misconfiguration.

---

## 3. COUCHE PROTOCOLE MINECRAFT (L7)

### 3.1 Machine a etats stricte -- BUILT-IN

**Probleme** : Les paquets envoyes dans le mauvais etat de connexion causent des deserialisations incorrectes, des crashes, ou des exploits.

**Implementation Warp** :
```
HANDSHAKE -> LOGIN -> CONFIGURATION -> PLAY
                                    -> CONFIGURATION (re-entree 1.20.2+)
```

**Regles strictes** :
- Chaque connexion a un etat atomique (`AtomicReference<ConnectionState>`)
- **Seuls les paquets valides pour l'etat actuel sont acceptes**
- Paquet invalide pour l'etat actuel = deconnexion immediate + log
- Les transitions d'etat ne sont possibles que via les paquets specifiques (LoginSuccess, FinishConfiguration, etc.)
- Pas de "fallback" ni de "retry" : une transition invalide est toujours fatale
- Timeout par phase (voir 2.3) : si la phase ne progresse pas dans le temps imparti, deconnexion

**Lecon Hypixel** : Un developpeur a documente 80+ bugs en implementant un proxy MC, dont la majorite etaient lies a des confusions d'etat protocole entre versions (Handshake -> Login -> Config -> Play). La rigueur de la machine a etats est la premiere defense.

### 3.2 Validation des paquets deserialises -- BUILT-IN

**Probleme** : Meme si un paquet est dans le bon etat, son contenu peut etre malveillant.

**Regles de validation pour chaque paquet deserialise** :
- **Chaines** : longueur <= 32767, encodage UTF-8 valide
- **VarInt** : <= 5 bytes, valeur dans la plage attendue pour le champ
- **Tableaux/collections** : taille maximale explicite par type de paquet
- **Coordonnees** : dans les limites du monde (-30M a +30M, 0 a 320 pour Y)
- **Identifiants (namespace:key)** : format valide, longueur limitee
- **NBT** : profondeur maximale de recursion + taille maximale (voir 3.3)
- **UUID** : format valide (pas de null UUID sauf si attendu)

**Chaque champ a une contrainte explicite** -- pas de deserialization ouverte.

### 3.3 Protection contre les bombes NBT -- BUILT-IN

**Contexte** : L'attaque NBT classique (ammaraskar, 2015) utilise 300 listes x 10 sous-listes x 5 niveaux = 30M objets. Compresse a 39 KB, decompresse a 26.6 MB.

**Implementation Warp** :
```
security:
  nbt:
    max-depth: 32              # profondeur max de recursion (Mojang: pas de limite)
    max-size-bytes: 2097152    # 2 MB max pour une structure NBT
    max-list-size: 65536       # taille max d'une liste NBT
    max-string-length: 32767   # longueur max d'une chaine NBT
```

**Implementation technique** :
- Compteur de profondeur incremente a chaque TAG_Compound/TAG_List ouvert
- Compteur de taille totale incremente a chaque byte lu
- Depassement = `DecoderException` immediate
- **Le blind forwarding contourne ce probleme** pour les paquets non-inspectes : les bytes passent tels quels sans deserialization NBT

### 3.4 Protection contre les bombes de decompression -- BUILT-IN

**Contexte** : Velocity #1742 (mars 2026) -- un attaquant envoie des paquets compresses qui se decompressent a ~8 MB chacun, provoquant un OOM kill. Netty GHSA-3p8m documente le meme probleme dans les codecs de decompression.

**Implementation Warp** :
```
security:
  compression:
    max-uncompressed-size: 8388608   # 8 MB (limite spec MC)
    max-compression-ratio: 200       # ratio max compresse:decompresse
    per-connection-decompression-budget-bytes: 67108864  # 64 MB/connexion/minute
```

**Implementation technique** :
- Le `Inflater` est wrappe dans un compteur qui suit les bytes decompresses
- Si `outputBytes / inputBytes > maxCompressionRatio` : deconnexion
- Budget de decompression par connexion par fenetre glissante (1 minute)
- Le blind forwarding reduit massivement la surface d'attaque : seuls les paquets enregistres par un plugin sont decompresses

**Avantage du blind forwarding** : ~90% des paquets en etat PLAY ne sont pas inspectes -> pas de decompression -> pas de vulnerabilite.

### 3.5 Validation des paquets Handshake/Status -- BUILT-IN

**Probleme** : Les paquets Handshake et Status sont les premiers recus et les plus exposes aux attaques automatisees.

**Regles** :
- **Handshake** :
  - Version protocole : entier valide (pas de VarInt negatif)
  - Adresse serveur : <= 255 caracteres, caracteres valides uniquement
  - Port : 1-65535
  - Next state : uniquement STATUS (1) ou LOGIN (2)
  - Tout autre champ = deconnexion immediate
- **Status Request** :
  - Paquet vide (0 bytes de payload) : rien d'autre n'est accepte
  - Un seul Status Request par connexion (pas de repetition)
- **Status Ping** :
  - Exactement 8 bytes de payload (long)
  - Un seul Ping par connexion
  - Reponse immediate puis deconnexion

### 3.6 Cache de Status/Ping -- CONFIGURABLE

**Probleme** : BungeeCord crash a 200-300 status requests/sec (#2854). Les ping floods sont le vecteur d'attaque le plus simple.

**Implementation Warp** (inspiration Gate Lite) :
```
status-cache:
  enabled: true
  ttl-ms: 5000               # cache pendant 5 secondes
  max-players-from-backends: true  # agreger les compteurs des backends
```

**Implementation technique** :
- La reponse Status est pre-serialisee et pre-compressee en memoire
- Les requetes Status ne touchent jamais les backends
- Le cache est invalide periodiquement (TTL) ou sur evenement (joueur join/leave)
- Un compteur global `statusRequestsPerSecond` declenche un rate limit si > seuil

---

## 4. ANTI-BOT ET ANTI-DDOS APPLICATIF

### 4.1 Framework anti-bot natif -- CONFIGURABLE

**Contexte** : Ni Velocity ni BungeeCord n'ont de protection anti-bot. LimboFilter (Elytrium) a prouve qu'un anti-bot au niveau proxy est 3.5x plus efficace en CPU qu'un mode online Velocity (20% vs 70% CPU sous 100k joins/sec).

**Architecture en couches** (chaque couche est franchie sequentiellement) :

```
Couche 1: Rate Limit IP (L4)          -- BUILT-IN
    |
Couche 2: Validation handshake        -- BUILT-IN
    |
Couche 3: ClientSettings + MC|Brand   -- CONFIGURABLE
    |
Couche 4: Verification comportementale -- PLUGIN (optionnel)
    |
Couche 5: Challenge CAPTCHA / Limbo   -- PLUGIN (optionnel)
    |
Connexion acceptee
```

### 4.2 Verification ClientSettings / MC|Brand -- CONFIGURABLE

**Principe** : Les vrais clients Minecraft envoient des paquets `ClientSettings` et `MC|Brand` lors de la connexion. Les bots simples ne le font pas.

```
security:
  antibot:
    require-client-settings: true
    require-mc-brand: true
    client-settings-timeout-ms: 5000  # doit arriver dans les 5s apres login
    known-brands:                     # marques connues (whitelist optionnelle)
      - "vanilla"
      - "fabric"
      - "forge"
      - "optifine"
```

**Implementation technique** :
- Apres Login Success, le proxy attend les paquets ClientSettings et MC|Brand
- Si absent dans le timeout : deconnexion
- Si marque inconnue et whitelist active : deconnexion
- Compatible avec Geyser/Floodgate (marques Bedrock autorisees)

### 4.3 API pour les challenges anti-bot avances -- PLUGIN

**Warp ne DOIT PAS integrer de CAPTCHA dans le core** -- c'est trop opinionne et evolue trop vite. A la place, fournir une API puissante.

**API proposee** :
```java
public interface AntiBotChallenge {
    /**
     * Appele quand une connexion suspecte est detectee.
     * Le plugin peut envoyer le joueur dans un serveur virtuel (Limbo),
     * lui presenter un CAPTCHA, ou faire un challenge comportemental.
     *
     * @return CompletableFuture<Boolean> true = joueur accepte, false = rejete
     */
    CompletableFuture<Boolean> challenge(IncomingConnection connection);
}

// Enregistrement
proxy.getAntiBotManager().registerChallenge(Priority.HIGH, myChallenge);
```

**Hooks disponibles pour les plugins** :
- `PreLoginEvent` : avant l'authentification Mojang
- `PostLoginEvent` : apres l'authentification, avant le routage
- `ConnectionSuspiciousEvent` : emis quand les heuristiques internes detectent un comportement suspect
- Acces a l'API Limbo (si implementee, Phase 6) pour les serveurs virtuels

**Exemples de plugins possibles** :
- CAPTCHA par map art (comme LimboFilter)
- Challenge de gravite (falling block)
- Verification de mouvement de souris
- Quiz textuel en chat
- Integration avec des services externes (CrowdSec, IPQualityScore)

### 4.4 GeoIP et anti-VPN -- PLUGIN

**Pourquoi PLUGIN et pas BUILT-IN** : Les bases GeoIP (MaxMind GeoLite2) et les APIs anti-VPN (proxycheck.io, IPQualityScore) ont des licences variees, necessitent des mises a jour frequentes, et different selon les juridictions.

**API Warp** :
```java
public interface IpIntelligenceProvider {
    CompletableFuture<IpIntelligence> lookup(InetAddress address);
}

public record IpIntelligence(
    String country,         // code ISO 3166-1
    String asn,             // numero AS
    String org,             // organisation
    boolean isVpn,
    boolean isProxy,
    boolean isDatacenter,
    boolean isTor,
    float riskScore         // 0.0 - 1.0
) {}
```

**Le core Warp fournit** :
- L'interface `IpIntelligenceProvider`
- Un systeme de cache pour les lookups (eviter les requetes repetees)
- La possibilite d'utiliser les resultats dans les regles de rate limiting

### 4.5 Systeme de reputation IP interne -- CONFIGURABLE

**Inspiration** : CrowdSec (reputation collaborative), Envoy (rate limiting adaptatif)

**Implementation Warp** :
```
security:
  ip-reputation:
    enabled: true
    initial-score: 100           # score de depart
    decay-rate-per-minute: 5     # regeneration naturelle
    penalties:
      failed-login: -20
      invalid-packet: -50
      rate-limit-hit: -10
      protocol-violation: -100   # score a 0 immediatement
    thresholds:
      warn: 50                   # log WARNING
      throttle: 30               # rate limit renforce
      block: 0                   # blocage temporaire
    block-duration-minutes: 30
```

**Implementation technique** :
- `ConcurrentHashMap<InetAddress, AtomicInteger>` pour le score
- Decay timer avec `ScheduledExecutorService`
- Les seuils declenchent des actions graduees (pas de ban instantane sauf violation grave)
- Persistance optionnelle (fichier ou Redis) pour survivre aux redemarrages
- Metriques exposees : `warp_ip_blocked_total`, `warp_ip_throttled_total`

---

## 5. FORWARDING ET AUTHENTIFICATION

### 5.1 Modern forwarding HMAC-SHA256 -- BUILT-IN

**Contexte** : Le forwarding BungeeCord (texte clair dans le handshake, BungeeCord #1328) est fondamentalement casse depuis 2015. Velocity modern forwarding utilise HMAC-SHA256.

**Implementation Warp** :
- **HMAC-SHA256** signe le payload (IP, UUID, properties du joueur)
- Le backend verifie la signature avant d'accepter la connexion
- **Secret par serveur** (Velocity #566 : un seul secret global est insuffisant)
  ```
  servers:
    lobby:
      address: "10.0.1.1:25565"
      forwarding-secret: "secret-lobby-unique"
    survival:
      address: "10.0.1.2:25565"
      forwarding-secret: "secret-survival-unique"
  ```

### 5.2 Rotation automatique des secrets -- CONFIGURABLE

**Probleme** : Velocity n'a pas de rotation automatique. Un secret compromis donne un acces permanent.

**Implementation Warp** :
```
security:
  forwarding:
    secret-rotation:
      enabled: false                  # opt-in
      rotation-interval-hours: 168    # 1 semaine
      grace-period-minutes: 30        # l'ancien secret reste valide pendant la rotation
      distribution: "file"            # ou "redis", "grpc"
```

**Mecanisme** :
- Pendant la periode de grace, le backend accepte l'ancien ET le nouveau secret
- Les secrets sont distribues aux backends via le canal headless proxy-backend
- Log d'audit pour chaque rotation

### 5.3 Mode de forwarding par serveur -- CONFIGURABLE

**Contexte** : Velocity #566 (2021, 0 progression) -- un seul mode pour tous les backends. Impossible d'avoir du modern forwarding pour Paper et du legacy pour un serveur Forge ancien.

**Implementation Warp** :
```
servers:
  lobby:
    forwarding-mode: MODERN       # HMAC-SHA256
    forwarding-secret: "xxx"
  forge-server:
    forwarding-mode: LEGACY       # BungeeCord-style (pour compatibilite)
    forwarding-secret: null
  vanilla-standalone:
    forwarding-mode: NONE         # pas de forwarding
```

### 5.4 Protection contre l'acces direct aux backends -- CONFIGURABLE

**Probleme** : Si un joueur accede directement a un backend (BungeeHack), le forwarding est contourne.

**Recommandations documentees** :
1. Firewall : seules les IPs du proxy atteignent les ports backend
2. Secret HMAC different par serveur (compromission d'un secret != compromission de tous)
3. Monitoring : le backend peut reporter les tentatives de connexion directe au proxy via le canal headless

---

## 6. RESILIENCE ET TOLERANCE AUX PANNES

### 6.1 Circuit breaker pour les connexions backend -- CONFIGURABLE

**Inspiration** : Envoy (5 types de circuit breakers), Resilience4j (sliding window + half-open state)

**Probleme** : Un backend defaillant ne doit pas degrader les autres serveurs ni le proxy lui-meme.

**Implementation Warp** :
```
resilience:
  circuit-breaker:
    enabled: true
    failure-rate-threshold: 50       # % d'echecs pour ouvrir le circuit
    slow-call-duration-ms: 5000      # qu'est-ce qu'un appel "lent" ?
    slow-call-rate-threshold: 80     # % d'appels lents pour ouvrir
    sliding-window-size: 20          # nombre d'appels dans la fenetre
    wait-duration-open-ms: 30000     # attente avant half-open
    permitted-calls-half-open: 3     # appels de test en half-open
```

**Machine a etats** :
```
CLOSED --(failure rate >= threshold)--> OPEN
OPEN   --(wait duration expires)------> HALF_OPEN
HALF_OPEN --(tests OK)----------------> CLOSED
HALF_OPEN --(tests KO)----------------> OPEN
```

**Par serveur backend** : chaque backend a son propre circuit breaker. Un backend defaillant est isole, les autres continuent normalement.

**Metriques** : `warp_circuit_breaker_state{server="lobby"}`, `warp_circuit_breaker_failures_total`

### 6.2 Bulkhead (isolation des ressources) -- BUILT-IN

**Inspiration** : Envoy (max connections per cluster), Resilience4j (semaphore/thread pool)

**Probleme** : Un backend qui consomme toutes les connexions du proxy affame les autres backends.

**Implementation Warp** :
```
resilience:
  bulkhead:
    max-connections-per-server: 2000       # limite par backend
    max-pending-connections-per-server: 50  # queue d'attente
    global-max-connections: 10000          # limite globale du proxy
```

**Implementation technique** :
- Semaphore par backend (pas de thread pool, le proxy est event-driven)
- Si le semaphore est plein : message "Serveur complet" au joueur
- Le global max protege le proxy lui-meme contre l'epuisement de file descriptors

### 6.3 Watchdog des event loops -- BUILT-IN

**Contexte** : BungeeCord #3358 + 6 issues liees -- le proxy cesse d'accepter les connexions sans log ni erreur. L'event loop est bloquee mais personne ne le detecte.

**Implementation Warp** :
```java
// Thread watchdog dedie
ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
    r -> new Thread(r, "warp-watchdog")
);

watchdog.scheduleAtFixedRate(() -> {
    for (EventLoop loop : workerGroup) {
        CompletableFuture<Void> heartbeat = new CompletableFuture<>();
        loop.submit(() -> heartbeat.complete(null));

        try {
            heartbeat.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.error("Event loop {} bloquee depuis >5s !", loop);
            dumpThreads(loop);
            metrics.counter("warp_eventloop_blocked_total").increment();
        }
    }
}, 10, 10, TimeUnit.SECONDS);
```

**Actions sur detection** :
1. Log CRITICAL avec stack trace de tous les threads
2. Increment du compteur metrique `warp_eventloop_blocked_total`
3. Tentative de recovery : fermer les connexions stagnantes sur cette event loop
4. Si le blocage persiste > 30s : alerte a la console + eventuel graceful restart

### 6.4 Detection de deadlock -- BUILT-IN

**Implementation** :
```java
// Utilisation de ThreadMXBean (standard JDK)
ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

watchdog.scheduleAtFixedRate(() -> {
    long[] deadlocked = tmx.findDeadlockedThreads();
    if (deadlocked != null) {
        logger.error("DEADLOCK DETECTE entre {} threads !", deadlocked.length);
        for (long id : deadlocked) {
            ThreadInfo info = tmx.getThreadInfo(id, Integer.MAX_VALUE);
            logger.error("Thread: {} - bloque sur: {} - possede: {}",
                info.getThreadName(), info.getLockInfo(),
                info.getLockedMonitors());
        }
        metrics.counter("warp_deadlock_detected_total").increment();
    }
}, 10, 30, TimeUnit.SECONDS);
```

### 6.5 Drain system multi-phase -- CONFIGURABLE

**Contexte** : Velocity #431 (22 reactions, 2021, 0 implementation) -- aucun mecanisme natif de drain.

**Implementation Warp** :
```
drain:
  enabled: true
  phases:
    - action: STOP_ACCEPTING_NEW_CONNECTIONS
      delay-seconds: 0
    - action: SEND_TRANSFER_PACKET     # 1.20.5+ : transfert natif
      target: "fallback-proxy.example.com:25565"
      delay-seconds: 10
    - action: KICK_REMAINING           # joueurs pre-1.20.5
      message: "Maintenance en cours, reconnectez-vous"
      delay-seconds: 60
    - action: SHUTDOWN
      delay-seconds: 5
  health-check:
    path: "/health"
    draining-response: 503            # HAProxy/K8s detecte le drain
```

**Integration** :
- Endpoint HTTP `/health` : `200 OK` (healthy), `503 Service Unavailable` (draining)
- Compatible HAProxy agent-check
- Compatible Kubernetes readiness/liveness probes
- Transfer Packet natif (1.20.5+) pour un transfert sans deconnexion

### 6.6 Self-healing automatique -- BUILT-IN

**Scenarios de recovery automatique** :

| Situation | Detection | Action |
|-----------|-----------|--------|
| Event loop bloquee > 5s | Watchdog heartbeat | Dump threads + fermer connexions stagnantes |
| Backend injoignable | Circuit breaker OPEN | Rediriger vers serveur fallback |
| Memoire > 85% heap | Moniteur JVM | Mode degrade (refuser nouvelles connexions, flush caches) |
| Memoire > 95% heap | Moniteur JVM | Drain d'urgence |
| Fuite memoire Netty detectee | `ResourceLeakDetector` | Log CRITICAL + dump heap optionnel |
| Deadlock | `ThreadMXBean` | Log + dump complet + potentiel restart |

---

## 7. SECURITE JAVA

### 7.1 Pas de deserialization Java -- BUILT-IN

**Contexte** : BleedingPipe (2023) a demontre que `ObjectInputStream` dans les mods permet l'execution de code arbitraire. OWASP classe l'insecure deserialization dans le top 10.

**Regles Warp** :
- **JAMAIS** d'utilisation de `java.io.ObjectInputStream` ou `java.io.ObjectOutputStream`
- Les seuls formats de serialisation autorises :
  - NBT (Minecraft, avec les limites de 3.3)
  - JSON (via Jackson ou equivalent, avec limites de profondeur/taille)
  - Protobuf / FlatBuffers (pour la communication inter-proxy)
- Verification par analyse statique (SpotBugs / Error Prone) en CI

### 7.2 Gestion memoire Netty -- BUILT-IN

**Contexte** : BungeeCord #2583 -- fuite memoire Netty (500MB -> 1.3GB -> crash).

**Implementation Warp** :
- **Direct buffers exclusivement** via `PooledByteBufAllocator.DEFAULT`
- `ResourceLeakDetector.setLevel(Level.SIMPLE)` en production, `Level.PARANOID` en dev
- **Regle absolue** : tout `ByteBuf` alloue doit etre release dans un bloc `try-finally`
  ```java
  ByteBuf buf = alloc.buffer();
  try {
      // utilisation
  } finally {
      buf.release();
  }
  ```
- Monitoring du pool direct : `PlatformDependent.usedDirectMemory()` expose comme metrique
- Write buffer water marks (voir 2.5) pour le backpressure

### 7.3 Cryptographie -- BUILT-IN

**Regles** :
- **SecureRandom** pour toute generation de secrets (forwarding secrets, tokens, nonces)
- Ne PAS utiliser `SecureRandom.getInstanceStrong()` cote serveur (peut bloquer sur l'entropie)
- Utiliser `SecureRandom()` par defaut (non-bloquant, cryptographiquement sur)
- **HMAC-SHA256** pour le forwarding (comme Velocity)
- **AES-128-CFB8** pour le chiffrement MC (spec protocole)
- Pas de crypto maison : utiliser les primitives JDK standard
- Revue de la generation de cles a chaque release

### 7.4 Isolation des plugins -- CONFIGURABLE

**Contexte** : Un plugin malveillant ou buggue ne doit pas compromettre le proxy.

**Implementation Warp** :
- ClassLoader isole par plugin (avec graphe de dependances declares)
- API restrictive : les plugins n'ont PAS acces a :
  - `java.io.ObjectInputStream`
  - Classes de lancement de processus OS
  - `System.exit()`
  - Reflection sur les classes internes de Warp (sauf API publique)
- Limites de ressources par plugin (optionnel) :
  - Timeout d'execution pour les event handlers (5s par defaut, configurable)
  - Compteur d'exceptions : un plugin qui leve trop d'exceptions est desactive

**Note post-Java 17** : Le Security Manager est deprecie. Utiliser a la place :
- Le module system Java (JPMS) pour limiter l'acces aux packages internes
- Des agents bytecode (ByteBuddy) pour intercepter les appels dangereux
- La review de code + la signature des plugins comme mesures complementaires

---

## 8. OBSERVABILITE SECURITAIRE

### 8.1 Metriques de securite -- BUILT-IN

**Toutes exposees via Prometheus/OpenTelemetry** :

```
# Connexions
warp_connections_total{state="accepted|rejected|timed_out"}
warp_connections_active{server="lobby"}
warp_connections_per_second{ip="x.x.x.x"}  # top-K, pas toutes les IPs

# Rate limiting
warp_rate_limit_hits_total{type="connection|packet|status"}
warp_rate_limit_active_blocks

# Protocole
warp_packets_invalid_total{reason="wrong_state|too_large|malformed|varint_overflow"}
warp_decompression_bytes_total
warp_decompression_ratio_exceeded_total

# Anti-bot
warp_bot_detected_total{layer="rate_limit|client_settings|brand|challenge"}
warp_login_attempts_total{result="success|failed|timeout"}
warp_ip_reputation_blocked_total

# Circuit breaker
warp_circuit_breaker_state{server="lobby",state="closed|open|half_open"}
warp_circuit_breaker_transitions_total{server="lobby",from="closed",to="open"}

# Resilience
warp_eventloop_blocked_total
warp_deadlock_detected_total
warp_heap_usage_ratio
warp_direct_memory_bytes

# Forwarding
warp_forwarding_hmac_failures_total{server="lobby"}
warp_proxy_protocol_rejected_total
```

### 8.2 Logging structure pour la forensique -- BUILT-IN

**Format** : JSON structure (via SLF4J + Logback avec encoder JSON)

```json
{
  "timestamp": "2026-03-31T14:23:01.456Z",
  "level": "WARN",
  "logger": "warp.security.antibot",
  "message": "Connection rejected: rate limit exceeded",
  "context": {
    "player_ip": "203.0.113.42",
    "player_name": null,
    "connection_state": "HANDSHAKE",
    "rate_limit_type": "connection_per_ip",
    "current_rate": 15,
    "limit": 5,
    "ip_reputation_score": 20,
    "geo_country": "XX",
    "action_taken": "disconnect"
  }
}
```

**Champs de contexte selon le type d'evenement** :
- **Securite** : IP, port, etat connexion, action prise, score reputation
- **Protocole** : packet ID, etat attendu, etat reel, taille paquet
- **Resilience** : serveur concerne, etat circuit breaker, latence
- **Performance** : decompression bytes, ratio, duree de traitement

**Rotation** : fichiers de log avec rotation quotidienne + retention configurable

### 8.3 Seuils d'alerte recommandes -- CONFIGURABLE

**Configurations par defaut (overridables)** :

```
alerts:
  thresholds:
    # Connexions
    connections-per-second-warning: 200
    connections-per-second-critical: 1000
    rejected-connections-ratio-warning: 0.3    # 30% rejetees

    # Protocole
    invalid-packets-per-minute-warning: 100
    decompression-ratio-exceeded-warning: 10   # 10 occurrences/min

    # Backends
    circuit-breaker-open-duration-warning-seconds: 60
    all-backends-unhealthy-critical: true

    # Ressources
    heap-usage-warning: 0.80
    heap-usage-critical: 0.95
    direct-memory-warning-mb: 512
    eventloop-blocked-critical: true
```

**Actions** :
- WARNING : log + increment metrique (Grafana dashboard)
- CRITICAL : log + increment metrique + evenement API (webhook, Discord, PagerDuty via plugin)

---

## 9. DEGRADATION GRACIEUSE SOUS CHARGE

### 9.1 Overload Manager (inspire d'Envoy) -- CONFIGURABLE

**Envoy a un systeme remarquable** : l'overload manager surveille les ressources et declenche des actions graduees. Warp doit implementer un systeme similaire.

```
overload:
  enabled: true
  monitors:
    heap-memory:
      max-heap-ratio: 0.95
    connections:
      max-global-connections: 10000

  actions:
    # A 80% heap : desactiver les fonctionnalites couteuses
    - trigger: { monitor: "heap-memory", threshold: 0.80 }
      actions:
        - DISABLE_STATUS_CACHE_REFRESH
        - REDUCE_LOGGING_VERBOSITY

    # A 90% heap : commencer a refuser les nouvelles connexions
    - trigger: { monitor: "heap-memory", threshold: 0.90 }
      actions:
        - REJECT_NEW_CONNECTIONS_PROBABILISTIC  # 50% des nouvelles connexions
        - FLUSH_INTERNAL_CACHES

    # A 95% heap : drain d'urgence
    - trigger: { monitor: "heap-memory", threshold: 0.95 }
      actions:
        - STOP_ACCEPTING_CONNECTIONS
        - EMERGENCY_DRAIN

    # Limite de connexions globale
    - trigger: { monitor: "connections", threshold: 0.95 }  # 9500/10000
      actions:
        - REJECT_NEW_CONNECTIONS
```

### 9.2 Load shedding intelligent -- CONFIGURABLE

**Pendant une attaque, le proxy doit continuer a servir les joueurs legitimes.**

**Strategies** :
1. **Priorite aux connexions existantes** : en mode surcharge, les nouvelles connexions sont rejetees mais les joueurs deja connectes ne sont pas affectes
2. **Rejet probabiliste** : au lieu de rejeter 100% des nouvelles connexions d'un coup, rejeter un pourcentage croissant (50% -> 75% -> 90% -> 100%)
3. **Priorite aux joueurs authentifies** : si le joueur est deja passe par Mojang auth, il a priorite
4. **Queue d'attente** : les joueurs rejetes voient un message "Serveur en forte charge, reessayez dans X secondes" au lieu d'un timeout silencieux

### 9.3 Thread pools bornes -- BUILT-IN

**Contexte** : Oracle CERT SEI (TPS00-J) recommande les thread pools bornes pour la degradation gracieuse.

**Implementation Warp** :
- Event loops Netty : `bossGroup` (1-2 threads), `workerGroup` (nombre de coeurs)
- Taches asynchrones des plugins : `FixedThreadPool` avec queue bornee
- Si la queue de taches est pleine : log WARNING + tache rejetee (pas de blocage de l'event loop)
- **Jamais de thread-per-connection** (contrairement a BungeeCord legacy)

---

## 10. MATRICE RECAPITULATIVE

### Mesures BUILT-IN (toujours actives, non desactivables)

| # | Mesure | Section |
|---|--------|---------|
| 1 | Machine a etats protocole stricte | 3.1 |
| 2 | Validation des paquets (chaines, VarInt, tableaux) | 3.2 |
| 3 | Limites de taille de payload (paquet, decompresse) | 2.4 |
| 4 | Protection bombes NBT (profondeur, taille) | 3.3 |
| 5 | Protection bombes de decompression (ratio, budget) | 3.4 |
| 6 | Validation handshake/status | 3.5 |
| 7 | HMAC-SHA256 forwarding | 5.1 |
| 8 | Pas de deserialization Java | 7.1 |
| 9 | Gestion memoire Netty (pooled, leak detector) | 7.2 |
| 10 | Cryptographie securisee (SecureRandom, pas de crypto maison) | 7.3 |
| 11 | Watchdog event loop | 6.3 |
| 12 | Detection deadlock | 6.4 |
| 13 | Bulkhead (isolation par serveur) | 6.2 |
| 14 | Self-healing automatique | 6.6 |
| 15 | Metriques de securite | 8.1 |
| 16 | Logging structure JSON | 8.2 |
| 17 | Thread pools bornes | 9.3 |
| 18 | Blind forwarding (reduit la surface d'attaque) | 3.4 |

### Mesures CONFIGURABLE (activees par defaut, parametrables)

| # | Mesure | Section | Defaut |
|---|--------|---------|--------|
| 1 | Rate limiting par IP (connexions) | 2.2 | ON, 3 simultanees, 5/sec |
| 2 | Timeouts par phase | 2.3 | ON, handshake 10s, read 30s |
| 3 | Backlog Netty | 2.5 | 256 |
| 4 | PROXY Protocol (trusted sources) | 2.6 | OFF |
| 5 | Cache Status/Ping | 3.6 | ON, TTL 5s |
| 6 | Verification ClientSettings/MC|Brand | 4.2 | ON |
| 7 | Reputation IP interne | 4.5 | ON |
| 8 | Secret par serveur | 5.1 | UN secret global |
| 9 | Rotation des secrets | 5.2 | OFF |
| 10 | Mode forwarding par serveur | 5.3 | MODERN global |
| 11 | Circuit breaker backend | 6.1 | ON, 50% failure rate |
| 12 | Bulkhead limites | 6.2 | 2000/serveur, 10000 global |
| 13 | Drain system | 6.5 | ON |
| 14 | Seuils d'alerte | 8.3 | Defauts documentes |
| 15 | Overload manager | 9.1 | ON, seuils progressifs |
| 16 | Isolation plugins | 7.4 | ON, timeout 5s |

### Mesures PLUGIN (API pour l'extension)

| # | Mesure | Section | API |
|---|--------|---------|-----|
| 1 | Challenges anti-bot avances (CAPTCHA, Limbo) | 4.3 | `AntiBotChallenge` |
| 2 | GeoIP / anti-VPN / anti-datacenter | 4.4 | `IpIntelligenceProvider` |
| 3 | Serveurs virtuels (Limbo) | 4.3 | LimboAPI (Phase 6) |
| 4 | Reputation IP externe (CrowdSec, etc.) | 4.5 | `IpIntelligenceProvider` |
| 5 | Webhooks d'alerte (Discord, PagerDuty) | 8.3 | Event system |
| 6 | Regles de blocage custom | 4.3 | `PreLoginEvent` |
| 7 | Verification comportementale (mouvement souris) | 4.1 | Event system PLAY state |

---

## 11. COMPARAISON AVEC L'EXISTANT

| Mesure | BungeeCord | Velocity | Gate | Warp |
|--------|-----------|----------|------|------|
| Rate limiting natif | Non | Non | Oui | **CONFIGURABLE** |
| Anti-bot natif | Non | Non | Partiel | **CONFIGURABLE + PLUGIN** |
| HMAC forwarding | Non (texte clair) | Oui (global) | N/A | **Oui (par serveur)** |
| Rotation secrets | Non | Non | Non | **CONFIGURABLE** |
| Protection decompression | Non | Non (issue #1742) | ? | **BUILT-IN** |
| Protection NBT bombs | Non | Non | N/A | **BUILT-IN** |
| Machine a etats stricte | Non | Partiel | Oui | **BUILT-IN** |
| Circuit breaker | Non | Non | Non | **CONFIGURABLE** |
| Bulkhead | Non | Non | Non | **CONFIGURABLE** |
| Watchdog event loop | Non | Non | Non | **BUILT-IN** |
| Drain system | Non | Non (#431) | Oui | **CONFIGURABLE** |
| Metriques securite | Non | Plugin | Oui | **BUILT-IN** |
| PROXY protocol securise | Partiel | Oui (bugs #1699) | Oui | **CONFIGURABLE** |
| Overload manager | Non | Non | Non | **CONFIGURABLE** |
| Cache status/ping | Non (#2854) | Non | Oui (Lite) | **CONFIGURABLE** |
| Logging structure | Non | Non | Partiel | **BUILT-IN** |

---

## 12. PRIORITE D'IMPLEMENTATION

### Phase 1 -- Protocol Foundation (DOIT etre present au premier commit)
1. Machine a etats stricte (3.1)
2. Limites de taille (2.4)
3. Validation VarInt/VarLong
4. Timeouts de base (2.3)

### Phase 2 -- Core Proxy
5. Protection decompression (3.4)
6. Protection NBT (3.3)
7. Validation paquets deserialises (3.2)
8. HMAC-SHA256 forwarding (5.1)

### Phase 3 -- Cloud-Native
9. Rate limiting par IP (2.2)
10. Cache status/ping (3.6)
11. Verification ClientSettings/MC|Brand (4.2)
12. Reputation IP interne (4.5)
13. Metriques Prometheus (8.1)
14. Logging structure (8.2)
15. PROXY Protocol securise (2.6)

### Phase 4 -- Plugin API
16. API AntiBotChallenge (4.3)
17. API IpIntelligenceProvider (4.4)
18. Hooks de securite (PreLoginEvent, etc.)

### Phase 5 -- Production Polish
19. Circuit breaker (6.1)
20. Bulkhead (6.2)
21. Watchdog event loop (6.3)
22. Detection deadlock (6.4)
23. Drain system (6.5)
24. Overload manager (9.1)
25. Self-healing (6.6)

### Phase 6 -- Advanced
26. Rotation des secrets (5.2)
27. LimboAPI (serveurs virtuels)
28. Integration CrowdSec / IP reputation externe

---

## REFERENCES

### Vulnerabilites et attaques
- [Microsoft -- MCCrash botnet](https://www.microsoft.com/en-us/security/blog/2022/12/15/mccrash-cross-platform-ddos-botnet-targets-private-minecraft-servers/)
- [Krebs -- Aisuru botnet 11 Tbps](https://krebsonsecurity.com/2025/10/ddos-botnet-aisuru-blankets-us-isps-in-record-ddos/)
- [ammaraskar -- Minecraft NBT vulnerability advisory](https://blog.ammaraskar.com/minecraft-vulnerability-advisory/)
- [Netty GHSA-3p8m -- decompression bomb](https://github.com/netty/netty/security/advisories/GHSA-3p8m-j85q-pgmj)
- [NVD -- CVE-2023-30859 (Triton RCE)](https://nvd.nist.gov/vuln/detail/CVE-2023-30859)
- [Malwarebytes -- BleedingPipe](https://www.malwarebytes.com/blog/news/2023/08/minecraft-mod-fans-beware-players-and-servers-at-risk-from-bleedingpipe-vulnerability)
- [MCPTool -- BungeeCord vulnerabilities](https://mcptool.net/docs/exploits/bungeecord)
- [SpigotMC -- BungeeCord exploit fix](https://www.spigotmc.org/wiki/bungeecord-exploit-fix/)
- [Mojang -- MC-79612 NBT accounting bug](https://bugs.mojang.com/browse/MC-79612)

### Proxys et references techniques
- [Velocity #1742 -- decompression OOM](https://github.com/PaperMC/Velocity/issues/1742)
- [Velocity #431 -- graceful shutdown](https://github.com/PaperMC/Velocity/issues/431)
- [Velocity #566 -- backend rearchitecture](https://github.com/PaperMC/Velocity/issues/566)
- [BungeeCord #1328 -- IP forwarding redesign](https://github.com/SpigotMC/BungeeCord/issues/1328)
- [BungeeCord #2854 -- DoS HTTP crash](https://github.com/SpigotMC/BungeeCord/issues/2854)
- [BungeeCord #3358 -- proxy stops accepting](https://github.com/SpigotMC/BungeeCord/issues/3358)
- [LimboFilter -- anti-bot solution](https://github.com/Elytrium/LimboFilter)
- [Gate -- Proxy Protocol security](https://www.mintlify.com/minekube/gate/security/proxy-protocol)
- [Hypixel Forums -- proxying MC challenges](https://hypixel.net/threads/i-thought-proxying-minecraft-would-take-a-weekend-it-took-13-days-heres-why.6064963/)
- [PaperMC -- Velocity modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding/)
- [PaperMC -- Securing your servers](https://docs.papermc.io/velocity/security/)
- [ashhhleyyy -- Velocity forwarding internals](https://ashhhleyyy.dev/blog/2022-06-27-velocity-information-forwarding)
- [TCPShield -- MC DDoS protection](https://tcpshield.com/)

### Patterns d'architecture
- [Envoy -- Circuit breaking](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/circuit_breaking)
- [Envoy -- Overload manager](https://www.envoyproxy.io/docs/envoy/latest/configuration/operations/overload_manager/overload_manager)
- [Envoy -- Global rate limiting](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/other_features/global_rate_limiting)
- [Resilience4j -- Circuit breaker](https://resilience4j.readme.io/docs/circuitbreaker)
- [Resilience4j -- Bulkhead](https://resilience4j.readme.io/docs/bulkhead)
- [NGINX -- Rate limiting](https://blog.nginx.org/blog/rate-limiting-nginx)
- [HAProxy -- PROXY Protocol spec](https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt)
- [APNIC -- PROXY Protocol security implications](https://blog.apnic.net/2025/07/01/a-first-look-at-the-proxy-protocol-and-its-security-implications/)
- [HAProxy -- Slowloris protection](https://www.haproxy.com/blog/protect-your-web-server-against-slowloris)
- [HAProxy -- Secure deployment guide](https://socfortress.medium.com/haproxy-secure-deployment-hardening-guide-e03a6ba16a54)

### Securite Java et Netty
- [OWASP -- Insecure Deserialization](https://owasp.org/www-community/vulnerabilities/Insecure_Deserialization)
- [Oracle -- Secure Coding Guidelines for Java SE](https://www.oracle.com/java/technologies/javase/seccodeguide.html)
- [Netty -- Security deep wiki](https://deepwiki.com/netty/netty/6-security)
- [Netty -- Resource leak detector](https://netty.io/wiki/reference-counted-objects.html)
- [Netty -- Traffic shaping](https://netty.io/4.0/api/io/netty/handler/traffic/ChannelTrafficShapingHandler.html)
- [Oracle -- SecureRandom Java 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/SecureRandom.html)
- [Black Duck -- Proper use of SecureRandom](https://www.blackduck.com/blog/proper-use-of-javas-securerandom.html)
- [SEI CERT -- TPS00-J Thread pools for graceful degradation](https://wiki.sei.cmu.edu/confluence/display/java/TPS00-J.+Use+thread+pools+to+enable+graceful+degradation+of+service+during+traffic+bursts)
- [DigitalOcean -- Deadlock detection in Java](https://www.digitalocean.com/community/tutorials/deadlock-in-java-example)

### Observabilite
- [Huntress -- Security Observability explained](https://www.huntress.com/cybersecurity-101/topic/security-observability)
- [Microsoft -- Observability for AI Systems (principes)](https://www.microsoft.com/en-us/security/blog/2026/03/18/observability-ai-systems-strengthening-visibility-proactive-risk-detection/)
- [CrowdSec -- Threat intelligence collaborative](https://app.crowdsec.net/cti)
- [IPQualityScore -- IP reputation](https://www.ipqualityscore.com/ip-reputation-check)
- [MaxMind -- GeoIP Anonymous IP](https://www.maxmind.com/en/geoip-anonymous-ip-database)

### Fuzzing et test de protocoles
- [Survey of Protocol Fuzzing (arxiv)](https://arxiv.org/html/2401.01568v2)
- [SoulFire -- Testing MC proxy networks](https://soulfiremc.com/blog/testing-minecraft-proxy-networks)
- [Minecraft Wiki -- Protocol packets](https://minecraft.wiki/w/Java_Edition_protocol/Packets)
