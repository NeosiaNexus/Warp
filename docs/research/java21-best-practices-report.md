# Rapport : Bonnes pratiques Java 21+ pour un proxy reseau haute performance

**Projet** : Warp -- proxy Minecraft open-source de reference
**Date** : 31 mars 2026
**Methode** : Recherche web approfondie (30+ sources), documentation officielle OpenJDK/Netty, benchmarks communautaires

---

## Table des matieres

1. [Java 21+ : fonctionnalites pertinentes pour le reseau](#1-java-21--fonctionnalites-pertinentes-pour-le-reseau)
2. [Netty 4.2+ : bonnes pratiques](#2-netty-42--bonnes-pratiques)
3. [Zero-copy et patterns haut debit](#3-zero-copy-et-patterns-haut-debit)
4. [Structure de projet moderne](#4-structure-de-projet-moderne)
5. [Gestion memoire pour serveurs longue duree](#5-gestion-memoire-pour-serveurs-longue-duree)
6. [Tests pour le code reseau](#6-tests-pour-le-code-reseau)
7. [Recommandations consolidees pour Warp](#7-recommandations-consolidees-pour-warp)

---

## 1. Java 21+ : fonctionnalites pertinentes pour le reseau

### 1.1 Virtual Threads (Project Loom) vs Event Loops (Netty)

**Verdict pour Warp : Netty event loop en coeur, virtual threads pour les taches annexes.**

Les benchmarks 2025 montrent :

| Architecture | Debit |
|---|---|
| Netty pur (event loop) | ~200K RPS |
| Micronaut (VT + Netty) | ~150K RPS |
| Virtual Threads (Tomcat) | ~140K RPS |
| Threads classiques | ~5K RPS |

**Pourquoi garder Netty comme coeur I/O de Warp :**

- Le proxy Minecraft est fondamentalement un **relais de paquets** -- l'event loop de Netty est optimise exactement pour ca (read -> forward -> write sans blocage)
- Les virtual threads n'apportent rien quand le code est deja non-bloquant
- Netty ne supportera pas nativement les virtual threads : *"virtual threads don't play nicely with JNI code or NIO, there is no point to invest in that area"* (Norman Maurer, maintainer Netty)
- Les virtual threads ajoutent un overhead de ~25-30% de throughput par rapport au Netty pur

**Ou utiliser les virtual threads dans Warp :**

```java
// Offloader le travail bloquant des plugins vers des virtual threads
private static final ExecutorService PLUGIN_EXECUTOR =
    Executors.newVirtualThreadPerTaskExecutor();

// Dans le pipeline d'evenements plugins :
PLUGIN_EXECUTOR.submit(() -> {
    plugin.onPlayerJoin(event);  // peut bloquer (DB, HTTP, etc.)
});
```

- **Traitement des evenements plugins** : les callbacks de plugins peuvent etre bloquants (acces BDD, HTTP, fichiers)
- **Handshake/authentification** : les appels a l'API Mojang sont bloquants
- **Commandes administratives** : taches ponctuelles a faible frequence
- **DNS resolution** : naturellement bloquant

**Recommandation** : Architecture hybride. Netty gere le I/O pur (event loop), les virtual threads gerent tout ce qui peut bloquer. Ne jamais bloquer un thread de l'event loop.

### 1.2 Structured Concurrency (JEP 453/505/525)

**Statut** : Preview dans JDK 21-24, evolue dans JDK 25 (6eme preview). API encore instable mais le concept est mature.

**Cas d'usage pour Warp** : toute operation fan-out ou le proxy doit interroger plusieurs services en parallele.

```java
// Authentification + resolution serveur en parallele
try (var scope = StructuredTaskScope.open()) {
    Subtask<PlayerProfile> authTask = scope.fork(() ->
        mojangAuth.authenticate(loginPacket));
    Subtask<ServerInfo> routeTask = scope.fork(() ->
        router.resolveTarget(loginPacket.hostname()));

    scope.join(); // attend les deux

    PlayerProfile profile = authTask.get();
    ServerInfo target = routeTask.get();
    connectToBackend(profile, target);
}
```

**Strategies de completion utiles pour Warp :**

| Strategie | Usage Warp |
|---|---|
| `Joiner.allSuccessfulOrThrow()` | Authentification (tous les checks doivent passer) |
| `Joiner.anySuccessfulResultOrThrow()` | Ping multi-serveur (premier qui repond) |
| `Joiner.awaitAll()` | Drain : notifier tous les backends en parallele |

**Avantages concrets :**
- Pas de thread leaks -- tous les subtasks terminent avant la fin du scope
- Annulation automatique des subtasks restants en cas d'echec
- Observabilite : les thread dumps montrent la hierarchie parent-enfant

**Recommandation** : Utiliser pour les operations de management (drain, health-check, routage). Eviter dans le hot-path de forwarding de paquets (overhead inutile la ou on ne bloque pas).

### 1.3 Scoped Values (JEP 446/506)

**Statut** : Finalise dans JDK 25 (JEP 506). Remplacant moderne de ThreadLocal.

**Probleme des ThreadLocal dans un proxy :**
- Avec les virtual threads, un ThreadLocal par connexion = millions de copies potentielles
- Les ThreadLocal sont mutables et difficiles a raisonner
- Pas de propagation propre vers les threads enfants

**Solution avec ScopedValue pour le contexte par-connexion :**

```java
// Declaration du contexte
public final class ConnectionContext {
    public static final ScopedValue<ConnectionContext> CURRENT = ScopedValue.newInstance();

    private final UUID playerId;
    private final InetSocketAddress remoteAddress;
    private final ProtocolVersion version;

    // Immuable par construction
    public ConnectionContext(UUID playerId, InetSocketAddress remoteAddress,
                             ProtocolVersion version) {
        this.playerId = playerId;
        this.remoteAddress = remoteAddress;
        this.version = version;
    }
    // getters...
}

// Liaison lors de la connexion
ScopedValue.where(ConnectionContext.CURRENT, new ConnectionContext(
    playerId, remoteAddr, protocolVersion
)).run(() -> {
    handleConnection(); // tout le code appele voit le contexte
});

// Acces dans n'importe quel handler appele
public void onPacketReceived(Packet pkt) {
    var ctx = ConnectionContext.CURRENT.get(); // zero lookup ThreadLocal
    log.debug("[{}] Received packet {}", ctx.playerId(), pkt);
}
```

**Avantages pour Warp :**
- **Immuabilite** : le contexte ne peut pas etre modifie accidentellement par un plugin
- **Performance** : acces plus rapide que ThreadLocal, pas de table de hachage par thread
- **Propagation** : les scoped values sont heritees par les threads enfants (virtual threads inclus)
- **Nettoyage automatique** : pas de memory leak possible, la valeur disparait en fin de scope

**Recommandation** : Adopter ScopedValue pour tout contexte de connexion, session, et requete. Grouper les valeurs dans un record pour maximiser l'efficacite du cache.

### 1.4 Foreign Function & Memory API (JEP 454)

**Statut** : Finalise dans JDK 22. Remplace JNI de facon sure et performante.

**Quand l'utiliser :**

| Scenario | FFM API ? | Justification |
|---|---|---|
| Compression zlib/zstd bulk | **OUI** | Un appel compresse un buffer entier -- overhead de crossing amortir |
| Chiffrement AES bulk (>1KB) | **OUI** | OpenSSL 10-100x plus rapide que JDK pour GCM |
| Comparaisons frequentes (callbacks) | **NON** | Chaque crossing coute 10-50ns, explose avec des millions d'appels |
| Crypto petits paquets (<64B) | **NON** | Overhead du crossing > gain natif |

**Benchmark de reference :**
- FFM + BLAS natif : 220x plus rapide que Java naif pour la multiplication matricielle
- FFM + qsort : 25x plus LENT que Java pour du tri (trop de callbacks)

**Usage concret pour Warp -- compression native :**

```java
// Au lieu de JNI pour zlib, utiliser FFM API
// Avantages : pas de compilation native, bounds checking, type safety
Arena arena = Arena.ofConfined();
MemorySegment input = arena.allocate(packetData.length);
input.copyFrom(MemorySegment.ofArray(packetData));

MemorySegment output = arena.allocate(maxCompressedSize);
// Appel natif zstd via FFM
long compressedSize = (long) zstdCompress.invoke(output, maxCompressedSize, input, packetData.length, compressionLevel);
```

**Recommandation pour Warp** : Envisager FFM API pour la compression zstd/zlib des paquets Minecraft (operation bulk, amortissement du crossing). Pour la crypto, utiliser `netty-tcnative-boringssl` qui gere deja l'interface native via BoringSSL. Ne pas reimplementer la couche crypto via FFM.

### 1.5 Pattern Matching, Sealed Classes, Records -- pour les types de paquets

**Ces fonctionnalites sont directement applicables au systeme de paquets Minecraft de Warp.**

```java
// Hierarchie de paquets avec sealed interfaces et records
public sealed interface Packet permits ClientPacket, ServerPacket {}

public sealed interface ClientPacket extends Packet
    permits HandshakePacket, LoginStartPacket, ChatPacket, MovePacket {}

public record HandshakePacket(
    int protocolVersion,
    String serverAddress,
    int serverPort,
    int nextState
) implements ClientPacket {}

public record LoginStartPacket(
    String username,
    UUID uuid
) implements ClientPacket {}

public record ChatPacket(String message) implements ClientPacket {}

public record MovePacket(
    double x, double y, double z,
    float yaw, float pitch,
    boolean onGround
) implements ClientPacket {}

// Traitement exhaustif -- le compilateur GARANTIT que tous les cas sont couverts
public void handlePacket(ClientPacket packet) {
    switch (packet) {
        case HandshakePacket hs -> processHandshake(hs);
        case LoginStartPacket login -> processLogin(login);
        case ChatPacket chat -> processChat(chat);
        case MovePacket move -> processMove(move);
        // Pas de default necessaire ! Si on ajoute un type, le compilateur OBLIGE a ajouter le case
    }
}

// Deconstruction de records dans le switch
public String describe(ClientPacket packet) {
    return switch (packet) {
        case HandshakePacket(var ver, var addr, _, var state) ->
            "Handshake v" + ver + " to " + addr + " state=" + state;
        case LoginStartPacket(var name, var uuid) ->
            "Login: " + name + " (" + uuid + ")";
        case ChatPacket(var msg) -> "Chat: " + msg;
        case MovePacket(var x, var y, var z, _, _, _) ->
            String.format("Move to (%.1f, %.1f, %.1f)", x, y, z);
    };
}
```

**Avantages pour Warp :**
- **Exhaustivite a la compilation** : impossible d'oublier un type de paquet
- **Immutabilite** : les records sont immutables par construction -- pas de corruption de paquet
- **Deconstruction** : extraction des champs sans getters dans le switch
- **Zero boilerplate** : equals/hashCode/toString generes automatiquement

**Attention performance** : eviter de creer des records dans le hot-path de forwarding (paquets passthrough). Les records sont parfaits pour les paquets que le proxy doit inspecter. Les paquets passthrough restent en ByteBuf bruts.

### 1.6 MemorySegment pour la gestion zero-copy

**MemorySegment permet d'acceder a de la memoire off-heap de facon sure sans ByteBuffer.**

Pour Warp, le principal interet est couple avec FFM API pour la compression native. Netty a deja sa propre gestion de buffers (ByteBuf) qui est plus mature et mieux integree a son pipeline. Ne pas tenter de remplacer ByteBuf par MemorySegment -- utiliser MemorySegment uniquement pour les interactions natives.

**Recommandation** : Rester sur ByteBuf de Netty pour le pipeline reseau. Utiliser MemorySegment seulement pour l'interface FFM (compression, eventuellement crypto custom).

---

## 2. Netty 4.2+ : bonnes pratiques

### 2.1 Architecture du pipeline pour un proxy

Le pipeline de Warp doit etre concu en couches claires :

```
[Client Channel]                          [Backend Channel]
     |                                          |
  SslHandler (optionnel)                    SslHandler
  LengthFieldDecoder                        LengthFieldDecoder
  CompressionDecoder (si active)            CompressionDecoder
  PacketDecoder                             PacketDecoder
  FlushConsolidationHandler                 FlushConsolidationHandler
  BackpressureHandler                       BackpressureHandler
  ProxyFrontHandler ---- lien ---- ProxyBackHandler
```

**Principes cles :**
- Handlers **stateless** partages entre channels (ex: decodeurs de longueur)
- Handlers **stateful** (ProxyFrontHandler) instancies par connexion
- **Retirer les handlers inutiles** : une fois le handshake termine, retirer les handlers de handshake
- Pipeline aussi **court que possible** : chaque handler = un appel virtuel supplementaire

### 2.2 Pooling de buffers (PooledByteBufAllocator)

```java
// Configuration optimisee pour un proxy
PooledByteBufAllocator allocator = new PooledByteBufAllocator(
    true,           // preferDirect = true (essentiel pour le I/O)
    /* nHeapArena */  0,  // pas d'arenas heap -- tout en direct
    /* nDirectArena */ Runtime.getRuntime().availableProcessors(),
    /* pageSize */     8192,
    /* maxOrder */     9,  // chunk size = 8192 << 9 = 4MB (defaut 16MB, reduire pour un proxy)
    /* smallCacheSize */ 256,
    /* normalCacheSize */ 64,
    /* useCacheForAllThreads */ false // ne cacher que pour les threads event loop
);

// Appliquer globalement
bootstrap.option(ChannelOption.ALLOCATOR, allocator);
bootstrap.childOption(ChannelOption.ALLOCATOR, allocator);
```

**Parametres systeme a configurer :**

| Propriete | Valeur recommandee | Justification |
|---|---|---|
| `io.netty.allocator.numDirectArenas` | Nombre de cores | Un arena par core = pas de contention |
| `io.netty.allocator.numHeapArenas` | 0 | Proxy = tout en direct buffers |
| `io.netty.allocator.useCacheForAllThreads` | false | Seuls les event loop threads ont besoin du cache |
| `io.netty.recycler.maxCapacityPerThread` | 256 | Limiter la memoire des recyclers |

### 2.3 Dimensionnement des EventLoopGroups

```java
// Boss group : 1 seul thread suffit (accepte les connexions)
EventLoopGroup bossGroup = new EpollEventLoopGroup(1);

// Worker group : le defaut 2*cores est souvent correct
// Pour un proxy : reduire si beaucoup de connexions idle
EventLoopGroup workerGroup = new EpollEventLoopGroup();

// IMPORTANT pour un proxy : partager le meme event loop entre
// le channel client et le channel backend d'une meme connexion
// Cela elimine le context-switching entre les deux directions
bootstrap.group(workerGroup) // le backend utilise le meme worker group
    .channel(EpollSocketChannel.class)
    .option(ChannelOption.ALLOCATOR, allocator);
```

**Regle critique pour un proxy** : enregistrer le channel backend sur le **meme EventLoop** que le channel client correspondant. Cela permet :
- Zero context-switch pour le forwarding
- Pas de synchronisation necessaire entre les deux directions
- L'ecriture vers le backend est executee immediatement sans mise en queue

```java
// Lors de la connexion au backend :
Bootstrap backendBootstrap = new Bootstrap()
    .group(clientChannel.eventLoop())  // <-- MEME event loop !
    .channel(EpollSocketChannel.class);
```

### 2.4 Write batching avec FlushConsolidationHandler

**Le flush est l'operation la plus couteuse** : chaque flush = un syscall potentiel.

```java
// Ajouter EN PREMIER dans le pipeline
pipeline.addFirst("flushConsolidation",
    new FlushConsolidationHandler(256, true));
// 256 = nombre de flushes avant flush force
// true = consolider meme quand aucun read en cours
```

**Fonctionnement** :
- Pendant un read loop actif, les flush() sont accumules et envoyes en une seule fois dans `channelReadComplete()`
- Quand `consolidateWhenNoReadInProgress=true`, les flushes hors read loop sont soumis comme taches separees, laissant le temps de batcher d'autres flushes

**Pattern complementaire -- write batching manuel :**

```java
// Au lieu de :
for (Packet pkt : packets) {
    ctx.writeAndFlush(pkt);  // MAUVAIS : un syscall par paquet
}

// Faire :
for (int i = 0; i < packets.size(); i++) {
    if (i == packets.size() - 1) {
        ctx.writeAndFlush(packets.get(i)); // flush uniquement le dernier
    } else {
        ctx.write(packets.get(i)); // write sans flush
    }
}
```

### 2.5 Backpressure et controle de flux

**La backpressure est CRITIQUE pour un proxy** : si le backend est lent, le proxy ne doit pas accumuler indefiniment les paquets en memoire.

```java
public class ProxyBackpressureHandler extends ChannelDuplexHandler {
    private final Channel pairedChannel;

    public ProxyBackpressureHandler(Channel pairedChannel) {
        this.pairedChannel = pairedChannel;
    }

    // Quand le canal d'ecriture devient non-writable, on arrete de lire l'autre cote
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        boolean writable = ctx.channel().isWritable();
        pairedChannel.config().setAutoRead(writable);
        ctx.fireChannelWritabilityChanged();
    }

    // Configuration des watermarks
    // High watermark = on arrete de lire (defaut 64KB)
    // Low watermark = on reprend la lecture (defaut 32KB)
}

// Configuration des watermarks dans le bootstrap
bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
    new WriteBufferWaterMark(32 * 1024, 64 * 1024));
```

**Mecanisme complet :**
1. Client envoie vite -> buffer d'ecriture vers backend se remplit
2. Buffer depasse le **high watermark** -> channel marque non-writable
3. `channelWritabilityChanged` -> `pairedChannel.setAutoRead(false)` -> on arrete de lire le client
4. Backend rattrape -> buffer passe sous le **low watermark** -> channel redevient writable
5. `channelWritabilityChanged` -> `pairedChannel.setAutoRead(true)` -> on reprend la lecture

### 2.6 Transport natif (epoll, kqueue, io_uring)

**Recommandation : epoll sur Linux, kqueue sur macOS. io_uring premature pour la production.**

```java
public static EventLoopGroup createEventLoopGroup(int threads) {
    if (Epoll.isAvailable()) {
        return new EpollEventLoopGroup(threads);
    } else if (KQueue.isAvailable()) {
        return new KQueueEventLoopGroup(threads);
    } else {
        return new NioEventLoopGroup(threads);
    }
}

public static Class<? extends ServerSocketChannel> serverChannelClass() {
    if (Epoll.isAvailable()) return EpollServerSocketChannel.class;
    if (KQueue.isAvailable()) return KQueueServerSocketChannel.class;
    return NioServerSocketChannel.class;
}
```

**epoll vs NIO** :
- Moins de GC (pas d'objets SelectionKey)
- Latence plus basse (~10-20% moins de tail latency)
- Support TCP_FASTOPEN, SO_REUSEPORT

**io_uring** :
- Theoriquement plus rapide pour certains workloads, mais les benchmarks avec Netty 4.2 montrent des regressions dans certains cas (29K RPS vs 230K pour epoll dans Reactor Netty)
- Des bugs de performance avec `auto-read=false` (critique pour la backpressure de proxy)
- Le support dans Netty est encore incubateur
- **Ne pas utiliser pour Warp en v1** -- reevaluer quand le support sera stabilise

### 2.7 SSL/TLS avec BoringSSL natif

```java
// Dependance Gradle :
// implementation 'io.netty:netty-tcnative-boringssl-static:2.0.69.Final'

SslContext sslContext = SslContextBuilder.forServer(certChain, privateKey)
    .sslProvider(SslProvider.OPENSSL)  // <-- BoringSSL natif
    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
    .applicationProtocolConfig(/* ... */)
    .build();
```

**Gains de performance :**
- JDK SSLEngine : 10-20 MB/s pour les cipher suites GCM
- BoringSSL via tcnative : ~200 MB/s (10-20x plus rapide), ~1 GB/s avec AES-NI

**Recommandation** : Toujours utiliser `netty-tcnative-boringssl-static` pour le TLS. Le binaire BoringSSL est inclus dans le JAR -- aucune installation native requise.

---

## 3. Zero-copy et patterns haut debit

### 3.1 CompositeByteBuf pour eviter les copies

**C'est le pattern le plus important pour Warp** : le blind forwarding des paquets passthrough.

```java
// MAUVAIS : copie des donnees
ByteBuf header = alloc.buffer(5);
header.writeVarInt(packetId);
header.writeVarInt(payload.readableBytes());
ByteBuf combined = alloc.buffer(header.readableBytes() + payload.readableBytes());
combined.writeBytes(header);
combined.writeBytes(payload);  // COPIE !

// BON : zero-copy avec CompositeByteBuf
CompositeByteBuf composite = alloc.compositeBuffer(2);
composite.addComponent(true, header);
composite.addComponent(true, payload.retain()); // retain car shared
// Le composite est une vue logique -- zero copie en memoire
```

**Attention** : `maxNumComponents` (defaut 16). Si depasse, Netty consolide automatiquement (= copie). Pour un proxy, 2-3 composants suffisent (header + payload).

### 3.2 Forwarding direct (le pattern cle de Warp)

```java
// Le coeur du blind forwarding : relayer le ByteBuf brut sans deserialization
public class BlindForwardHandler extends ChannelInboundHandlerAdapter {
    private final Channel targetChannel;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (targetChannel.isActive()) {
            // Le ByteBuf passe tel quel -- ZERO copie, ZERO deserialization
            targetChannel.write(msg); // pas de flush ici, voir batching
        } else {
            ReferenceCountUtil.release(msg); // cleanup si le backend est down
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        targetChannel.flush(); // un seul flush apres tout le read batch
        ctx.fireChannelReadComplete();
    }
}
```

### 3.3 Direct buffers vs Heap buffers

| Critere | Direct Buffer | Heap Buffer |
|---|---|---|
| I/O performance | Optimal (pas de copie JVM -> natif) | Copie supplementaire |
| Allocation | Plus lent (syscall mmap) | Plus rapide (GC gere) |
| Avec pooling | Cout amorti, excellent | Inutile pour un proxy |
| GC impact | Aucun (off-heap) | Contribue a la pression GC |

**Recommandation Warp** : 100% direct buffers avec PooledByteBufAllocator. Le cout d'allocation est amorti par le pooling.

### 3.4 Reference counting -- discipline stricte

```java
// Regle d'or : celui qui cree ou retain() DOIT release()
// Exception : quand on passe a ctx.write() -- Netty release apres l'envoi

// Pattern correct dans un handler :
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    try {
        if (shouldForward(buf)) {
            ctx.fireChannelRead(buf.retain()); // retain car on passe a un autre handler
        }
    } finally {
        buf.release(); // toujours release notre reference
    }
}

// Pour les slices :
ByteBuf slice = buf.retainedSlice(offset, length); // retainedSlice = safe
// NE PAS utiliser buf.slice() si le slice survit au scope courant
```

---

## 4. Structure de projet moderne

### 4.1 Gradle vs Maven en 2025 -- Verdict : Gradle

| Critere | Gradle | Maven |
|---|---|---|
| Performance build | 2-7x plus rapide (incrementiel, cache) | Lineaire, pas de cache |
| Flexibilite | DSL Kotlin/Groovy, plugins custom | XML rigide |
| Multi-module | Excellent, builds paralleles | Correct mais plus lent |
| Adoption 2025 | 48% (JetBrains survey) | 52% |
| Open source | Prefere par les projets modernes | Heritage |

**Recommandation** : Gradle avec Kotlin DSL. Plus rapide, plus expressif, meilleur pour un projet open source moderne.

### 4.2 Structure multi-module recommandee pour Warp

```
warp/
├── settings.gradle.kts
├── build-logic/                    # Convention plugins partages
│   └── src/main/kotlin/
│       ├── warp.java-conventions.gradle.kts
│       └── warp.publish-conventions.gradle.kts
├── warp-api/                       # API publique (pour les plugins)
│   ├── build.gradle.kts
│   └── src/main/java/
│       └── dev.warp.api/
│           ├── event/              # Evenements (records immutables)
│           ├── player/             # Interface Player
│           ├── server/             # Interface Server
│           ├── plugin/             # Interface Plugin, annotations
│           └── command/            # Systeme de commandes
├── warp-protocol/                  # Encodage/decodage du protocole MC
│   ├── build.gradle.kts
│   └── src/main/java/
│       └── dev.warp.protocol/
│           ├── packet/             # Sealed interfaces + records
│           ├── codec/              # Encoders/decoders Netty
│           └── version/            # Support multi-version
├── warp-proxy/                     # Implementation du proxy
│   ├── build.gradle.kts
│   └── src/main/java/
│       └── dev.warp.proxy/
│           ├── connection/         # Gestion des connexions
│           ├── forwarding/         # Blind forwarding, smart forwarding
│           ├── pipeline/           # Pipeline Netty, handlers
│           ├── drain/              # Systeme de drain
│           └── config/             # Configuration
├── warp-native/                    # Compression native (FFM API)
│   ├── build.gradle.kts
│   └── src/main/java/
│       └── dev.warp.native/
│           └── compression/        # zstd/zlib via FFM
└── warp-launcher/                  # Point d'entree, chargement de plugins
    ├── build.gradle.kts
    └── src/main/java/
        └── dev.warp.launcher/
```

### 4.3 Convention plugin Gradle (build-logic)

```kotlin
// build-logic/src/main/kotlin/warp.java-conventions.gradle.kts
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "--enable-preview",   // pour structured concurrency, scoped values
        "-Xlint:all",
        "-Xlint:-preview"
    ))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}
```

### 4.4 JPMS (Java Platform Module System) -- pragmatisme

**Recommandation : module-info.java pour les modules publics (API), pas pour les modules internes.**

- `warp-api` : **OUI** -- c'est une API consommee par des plugins tiers, l'encapsulation forte est un avantage
- `warp-protocol` : **OUI** -- module reutilisable, exporte seulement les types publics
- `warp-proxy` : **NON** -- module interne, les frameworks de test et plugins ont besoin de reflection
- `warp-native` : **OUI** -- encapsule l'usage de FFM API

**Justification** : la majorite de l'ecosysteme Java n'a pas adopte JPMS. Imposer des modules partout ajoute de la friction sans gain clair pour les parties internes. L'API publique beneficie reellement de l'encapsulation.

---

## 5. Gestion memoire pour serveurs longue duree

### 5.1 Choix du GC -- Verdict : Generational ZGC

| GC | Pauses | Debit | Heap recommande | Pour Warp ? |
|---|---|---|---|---|
| G1 | 50-500ms | Bon | 4-16 GB | NON -- pauses trop longues pour un proxy |
| Shenandoah | 1-10ms | Bon | Toute taille | BON -- alternative si ZGC indisponible |
| ZGC Generational | <1ms (0.1-0.5ms) | Excellent | Toute taille | **RECOMMANDE** |

**Pourquoi ZGC Generational pour Warp :**
- Pauses sub-milliseconde = aucun tick rate spike pour les joueurs
- Le mode generational (defaut JDK 23+) ameliore le debit par rapport au ZGC classique
- Les paquets Minecraft sont des objets a vie courte = le jeune generateur est ideal
- Adaptatif : ajuste dynamiquement les generations, les threads GC, les seuils

**Flags JVM recommandes pour Warp :**

```bash
java \
  -XX:+UseZGC \
  -XX:+ZGenerational \            # defaut depuis JDK 23, explicite pour JDK 21-22
  -Xmx4g \                        # adapter selon la charge
  -XX:SoftMaxHeapSize=3g \         # ZGC essaie de rester sous 3g, peut monter a 4g si necessaire
  -XX:+AlwaysPreTouch \            # pre-touche toute la memoire au demarrage (latence plus stable)
  -XX:+UseTransparentHugePages \   # reduit les TLB misses
  -XX:+DisableExplicitGC \         # empeche System.gc() des plugins
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/warp/ \
  --enable-preview \               # pour structured concurrency, scoped values
  -jar warp.jar
```

### 5.2 Gestion de la memoire off-heap

Le proxy Warp aura beaucoup de memoire off-heap (direct buffers Netty). A surveiller :

```java
// Exposer les metriques Netty
PooledByteBufAllocatorMetric metric = PooledByteBufAllocator.DEFAULT.metric();
// metric.usedDirectMemory()
// metric.usedHeapMemory()
// metric.numDirectArenas()

// Monitoring JMX des direct buffers NIO
ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
    .forEach(pool -> {
        log.info("Pool {}: count={}, used={}, capacity={}",
            pool.getName(), pool.getCount(),
            pool.getMemoryUsed(), pool.getTotalCapacity());
    });
```

**Flags pour la memoire directe :**

```bash
-XX:MaxDirectMemorySize=2g        # limiter la memoire directe
-Dio.netty.maxDirectMemory=0      # laisser Netty utiliser la limite JVM
```

### 5.3 Detection de fuites memoire

**Netty ResourceLeakDetector** -- outil essentiel :

| Niveau | Usage | Overhead |
|---|---|---|
| `DISABLED` | Production haute charge | Aucun |
| `SIMPLE` | Production normale (defaut) | Faible |
| `ADVANCED` | Staging / canary | Eleve |
| `PARANOID` | Tests unitaires / CI | Tres eleve (10x) |

```bash
# En developpement / CI :
-Dio.netty.leakDetectionLevel=PARANOID

# En staging :
-Dio.netty.leakDetectionLevel=ADVANCED

# En production :
-Dio.netty.leakDetectionLevel=SIMPLE
```

**Recommandation** : executer tous les tests en PARANOID. Faire un canary en ADVANCED avant chaque release. Ne jamais deployer avec DISABLED.

### 5.4 Java Flight Recorder (JFR)

```bash
# Toujours actif en production (overhead <1%)
-XX:StartFlightRecording=dumponexit=true,filename=/var/log/warp/warp.jfr,settings=profile

# Pour debugger un leak memoire specifique :
-XX:StartFlightRecording=name=leak,settings=profile,
  jdk.ObjectAllocationSample#enabled=true,
  jdk.OldObjectSample#enabled=true
```

**JFR capture** :
- Allocations memoire (heap + off-heap via event custom)
- GC pauses et activite
- Thread states et contentions
- Latences I/O
- CPU hotspots

**Recommandation** : integrer JFR des le jour 1. Ajouter des events JFR custom pour les metriques Warp (connexions, paquets/sec, latence forwarding).

---

## 6. Tests pour le code reseau

### 6.1 Tests unitaires avec EmbeddedChannel

**EmbeddedChannel est le pilier des tests Netty** : il simule un channel complet sans reseau reel.

```java
class PacketDecoderTest {

    @Test
    void shouldDecodeHandshakePacket() {
        // Arrange
        EmbeddedChannel channel = new EmbeddedChannel(
            new VarIntFrameDecoder(),
            new PacketDecoder(ProtocolState.HANDSHAKE)
        );

        // Act
        ByteBuf rawPacket = encodeHandshake(767, "mc.example.com", 25565, 2);
        channel.writeInbound(rawPacket);

        // Assert
        HandshakePacket packet = channel.readInbound();
        assertNotNull(packet);
        assertEquals(767, packet.protocolVersion());
        assertEquals("mc.example.com", packet.serverAddress());
        assertEquals(2, packet.nextState());

        // Verifier qu'il n'y a pas de fuite memoire
        assertFalse(channel.finish());
    }

    @Test
    void shouldHandleFragmentedInput() {
        EmbeddedChannel channel = new EmbeddedChannel(
            new VarIntFrameDecoder(),
            new PacketDecoder(ProtocolState.HANDSHAKE)
        );

        ByteBuf full = encodeHandshake(767, "mc.example.com", 25565, 2);
        int mid = full.readableBytes() / 2;

        // Envoyer en deux fragments
        channel.writeInbound(full.readRetainedSlice(mid));
        assertNull(channel.readInbound()); // pas encore complet

        channel.writeInbound(full);
        HandshakePacket packet = channel.readInbound();
        assertNotNull(packet); // maintenant complet
    }
}
```

**Pattern pour tester le forwarding :**

```java
@Test
void shouldForwardPacketWithoutCopy() {
    EmbeddedChannel frontend = new EmbeddedChannel();
    EmbeddedChannel backend = new EmbeddedChannel();

    BlindForwardHandler forwarder = new BlindForwardHandler(backend);
    frontend.pipeline().addLast(forwarder);

    ByteBuf packet = Unpooled.wrappedBuffer(new byte[]{0x01, 0x02, 0x03});
    int refCnt = packet.refCnt();

    frontend.writeInbound(packet.retain());

    // Le paquet doit etre ecrit vers le backend
    ByteBuf forwarded = backend.readOutbound();
    assertNotNull(forwarded);
    assertEquals(packet, forwarded);

    // Cleanup
    forwarded.release();
}
```

### 6.2 Tests d'integration avec vrais sockets

```java
class ProxyIntegrationTest {

    private static EventLoopGroup group;

    @BeforeAll
    static void setup() {
        group = new NioEventLoopGroup(2);
    }

    @Test
    void shouldProxyConnectionEndToEnd() throws Exception {
        // Demarrer un faux serveur backend
        Channel backend = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new EchoServerHandler())
            .bind(0).sync().channel();
        int backendPort = ((InetSocketAddress) backend.localAddress()).getPort();

        // Demarrer le proxy
        WarpProxy proxy = new WarpProxy(proxyConfig(backendPort));
        proxy.start().join();

        // Client se connecte au proxy
        Channel client = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new TestClientHandler())
            .connect("localhost", proxy.port()).sync().channel();

        // Envoyer un paquet et verifier qu'il arrive
        ByteBuf request = Unpooled.wrappedBuffer("test".getBytes());
        client.writeAndFlush(request).sync();

        // ... assertions sur la reponse ...

        proxy.shutdown().join();
        backend.close().sync();
    }

    @AfterAll
    static void tearDown() {
        group.shutdownGracefully();
    }
}
```

### 6.3 Benchmarks avec JMH

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class PacketDecoderBenchmark {

    private EmbeddedChannel channel;
    private byte[] rawPacket;

    @Setup(Level.Iteration)
    public void setup() {
        channel = new EmbeddedChannel(
            new VarIntFrameDecoder(),
            new PacketDecoder(ProtocolState.PLAY)
        );
        rawPacket = generateMovePacket();
    }

    @Benchmark
    public Object decodePacket() {
        ByteBuf buf = Unpooled.wrappedBuffer(rawPacket);
        channel.writeInbound(buf);
        Object result = channel.readInbound();
        ReferenceCountUtil.release(result);
        return result;
    }

    @TearDown(Level.Iteration)
    public void teardown() {
        channel.finish();
    }
}
```

**Metriques a benchmarker pour Warp :**
- Paquets decodes/sec (deserialization)
- Paquets forwarded/sec (blind forwarding)
- Latence aller-retour (client -> proxy -> backend -> proxy -> client)
- Memoire par connexion
- Temps de compression/decompression

### 6.4 Property-based testing avec jqwik

**Ideal pour tester les codecs de protocole** : generer des paquets aleatoires et verifier les invariants.

```java
class PacketCodecPropertyTest {

    @Property(tries = 10_000)
    void encodeDecodeShouldBeIdentity(
            @ForAll @IntRange(min = 0, max = 767) int protocolVersion,
            @ForAll @StringLength(min = 1, max = 255) String serverAddress,
            @ForAll @IntRange(min = 1, max = 65535) int port,
            @ForAll @IntRange(min = 1, max = 2) int nextState) {

        HandshakePacket original = new HandshakePacket(
            protocolVersion, serverAddress, port, nextState);

        // Encode
        EmbeddedChannel encoder = new EmbeddedChannel(new PacketEncoder());
        encoder.writeOutbound(original);
        ByteBuf encoded = encoder.readOutbound();

        // Decode
        EmbeddedChannel decoder = new EmbeddedChannel(
            new VarIntFrameDecoder(),
            new PacketDecoder(ProtocolState.HANDSHAKE));
        decoder.writeInbound(encoded);
        HandshakePacket decoded = decoder.readInbound();

        // Invariant : encode(decode(x)) == x
        assertEquals(original, decoded);

        encoder.finish();
        decoder.finish();
    }

    @Property(tries = 5_000)
    void shouldHandleAnyFragmentation(
            @ForAll @IntRange(min = 1, max = 10) int fragments) {
        // Generer un paquet valide
        ByteBuf full = encodeValidPacket();
        int totalBytes = full.readableBytes();

        EmbeddedChannel channel = new EmbeddedChannel(
            new VarIntFrameDecoder(),
            new PacketDecoder(ProtocolState.PLAY));

        // Fragmenter aleatoirement et envoyer
        Random rng = new Random();
        while (full.isReadable()) {
            int chunkSize = Math.min(
                rng.nextInt(totalBytes / fragments) + 1,
                full.readableBytes());
            channel.writeInbound(full.readRetainedSlice(chunkSize));
        }
        full.release();

        // Le paquet doit etre reconstitue correctement
        Object decoded = channel.readInbound();
        assertNotNull(decoded);
        ReferenceCountUtil.release(decoded);
        channel.finish();
    }
}
```

**Proprietes a tester pour le protocole Minecraft :**
- `encode(decode(bytes)) == bytes` (roundtrip)
- Toute fragmentation de l'input produit le meme resultat
- Les VarInt/VarLong invalides sont rejetes proprement
- Les paquets trop grands sont rejetes (DoS protection)
- Les strings trop longues sont tronquees/rejetees

---

## 7. Recommandations consolidees pour Warp

### Decisions d'architecture

| Decision | Choix | Justification |
|---|---|---|
| I/O core | Netty event loop | Debit max pour le forwarding de paquets |
| Taches bloquantes | Virtual threads | Simplicite pour plugins, auth, DNS |
| Contexte connexion | ScopedValue | Immutable, performant, safe avec VT |
| Operations fan-out | Structured Concurrency | Drain, health-check, routage parallele |
| Types de paquets | Sealed interfaces + records | Exhaustivite, immutabilite, zero boilerplate |
| Compression native | FFM API (zstd) | Remplace JNI, safe, performant pour le bulk |
| TLS | netty-tcnative-boringssl | 10-20x plus rapide que JDK SSLEngine |
| Transport | epoll (Linux) / kqueue (macOS) / NIO (fallback) | Moins de GC, moins de latence |
| GC | ZGC Generational | Pauses <1ms, ideal pour un proxy |
| Build | Gradle Kotlin DSL | Rapide, flexible, multi-module natif |
| JPMS | Seulement pour warp-api et warp-protocol | Pragmatisme : encapsulation ou necessaire |
| Leak detection | ResourceLeakDetector PARANOID en CI | Zero tolerance pour les leaks |
| Observabilite | JFR always-on | <1% overhead, indispensable pour le debug |

### Priorites d'implementation

1. **Phase 1** : Pipeline Netty de base avec epoll, PooledByteBufAllocator, FlushConsolidation
2. **Phase 2** : Blind forwarding zero-copy avec backpressure
3. **Phase 3** : Systeme de paquets (sealed interfaces + records) pour les paquets inspectes
4. **Phase 4** : Virtual threads pour les plugins + ScopedValue pour le contexte
5. **Phase 5** : Compression native via FFM API
6. **Phase 6** : Tests property-based, benchmarks JMH, JFR custom events

---

## Sources

### Virtual Threads et Netty
- [The Virtual Thread Revolution: Modern Java Server Architecture in 2025](https://medium.com/@kunalsh23/the-virtual-thread-revolution-modern-java-server-architecture-in-2025-f6e1ef27f383)
- [Netty issue #14636: Virtual threads support](https://github.com/netty/netty/issues/14636)
- [Project Loom's Virtual Threads: Why Blocking Code Is Cool Again](https://www.javacodegeeks.com/2026/02/project-looms-virtual-threads-why-blocking-code-is-cool-again.html)
- [Maximizing Performance with Netty and Reactive Programming](https://medium.com/object-computing/maximizing-performance-with-netty-and-reactive-programming-in-java-dc984a4316eb)
- [From 1 to 100K Requests: Netty and Virtual Threads](https://diogodssantos.medium.com/from-1-to-100-000-requests-in-java-4-hour-journey-scaling-using-netty-and-virtual-threads-777df87f3c7c)

### Structured Concurrency
- [JEP 525: Structured Concurrency (Sixth Preview)](https://openjdk.org/jeps/525)
- [Structured Concurrency in Java 25: Complete Guide](https://medium.com/@code.wizzard01/structured-concurrency-in-java-25-complete-guide-with-examples-11e049637709)
- [Structured Concurrency with StructuredTaskScope](https://www.happycoders.eu/java/structured-concurrency-structuredtaskscope/)
- [JEP 505: Structured Concurrency Fifth Preview](https://www.infoq.com/news/2025/05/jep-505-concurrency-preview-5/)

### Scoped Values
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506)
- [Scoped Values in Java 25: A Safer Alternative to ThreadLocal](https://medium.com/@william.cesar.santos1/scoped-values-in-java-25-jep-506-a-safer-alternative-to-threadlocal-47b07dca7c5a)
- [ThreadLocal vs. Scoped Values: The Virtual Thread Migration](https://www.javacodegeeks.com/2026/03/threadlocal-vs-scoped-valuesthe-virtual-thread-migrationno-one-warned-you-about.html)

### Foreign Function & Memory API
- [When Does Java's FFM API Actually Make Sense?](https://bazlur.ca/2025/12/14/when-does-javas-foreign-function-memory-api-actually-make-sense/)
- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [Mastering the FFM API in Java 21](https://medium.com/code-brew-java-insights/mastering-the-foreign-function-memory-api-in-java-21-1c2640015d1f)

### Netty Best Practices
- [Netty Best Practices Distilled](https://www.antonkharenko.com/2015/08/netty-best-practices-distilled.html)
- [Upgrading a Reverse Proxy from Netty 3 to 4 (Square)](https://developer.squareup.com/blog/upgrading-a-reverse-proxy-from-netty-3-to-4/)
- [Netty Buffer Management (DeepWiki)](https://deepwiki.com/netty/netty/3-buffer-management)
- [Netty Event Loop & Thread Model (DeepWiki)](https://deepwiki.com/netty/netty/2.2-event-loop-and-thread-model)
- [Netty SSL/TLS Implementation (DeepWiki)](https://deepwiki.com/netty/netty/6.1-ssltls-implementation)
- [BungeeCord FlushConsolidation PR #3393](https://github.com/SpigotMC/BungeeCord/pull/3393)

### io_uring
- [Netty io_uring performance issues (GitHub)](https://github.com/netty/netty-incubator-transport-io_uring/issues/152)
- [Reactor Netty io_uring degradation (GitHub)](https://github.com/reactor/reactor-netty/issues/3833)
- [io_uring vs epoll (Alibaba Cloud)](https://www.alibabacloud.com/blog/io-uring-vs--epoll-which-is-better-in-network-programming_599544)

### Zero-Copy
- [Netty Zero-Copy Principle](https://codebase.city/samples/zero-copy-principle-of-netty.html)
- [ByteBuf API & Implementations (DeepWiki)](https://deepwiki.com/netty/netty/3.1-bytebuf-api-and-implementations)
- [How Java Achieves Zero-Copy File Transfer](https://javabulletin.substack.com/p/how-java-achieves-zero-copy-file)

### GC Tuning
- [ZGC vs Shenandoah: Ultra-Low Latency GC](https://www.javacodegeeks.com/2025/04/zgc-vs-shenandoah-ultra-low-latency-gc-for-java.html)
- [G1 vs ZGC vs Shenandoah (Java Code Geeks)](https://www.javacodegeeks.com/2025/08/java-gc-performance-g1-vs-zgc-vs-shenandoah.html)
- [Lower Java Tail Latencies with ZGC (Gunnar Morling)](https://www.morling.dev/blog/lower-java-tail-latencies-with-zgc/)
- [ZGC Tuning Guide (Oracle)](https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html)
- [Deep Dive: Pauseless GC in Java 25](https://andrewbaker.ninja/2025/12/03/deep-dive-pauseless-garbage-collection-in-java-25/)

### Project Structure
- [Structuring and Organizing Gradle Projects](https://docs.gradle.org/current/userguide/organizing_gradle_projects.html)
- [Gradle vs Maven Performance](https://gradle.org/gradle-and-maven-performance/)
- [Modular Java Builds with Gradle](https://dev.to/jolisper/modular-java-builds-with-gradle-from-setup-to-strategy-17nh)
- [JPMS Migration Strategies](https://www.javacodegeeks.com/2025/11/java-platform-module-system-migration-strategies-for-legacy-applications.html)

### Testing
- [Testing Netty with EmbeddedChannel (Baeldung)](https://www.baeldung.com/testing-netty-embedded-channel)
- [Netty Microbenchmarks](https://netty.io/wiki/microbenchmarks.html)
- [Property-Based Testing with jqwik (Baeldung)](https://www.baeldung.com/java-jqwik-property-based-testing)
- [jqwik User Guide](https://jqwik.net/docs/current/user-guide.html)

### Memory & Observability
- [JFR and Mission Control: Profiling Production JVMs](https://www.javacodegeeks.com/2025/07/java-flight-recorder-and-mission-control-profiling-production-jvms.html)
- [Netty Reference Counted Objects](https://netty.io/wiki/reference-counted-objects.html)
- [Netty ByteBuf Memory Leak Story (Logz.io)](https://logz.io/blog/netty-bytebuf-memory-leak/)

### Minecraft Proxies
- [Velocity (PaperMC)](https://papermc.io/software/velocity/)
- [Velocity (Grokipedia)](https://grokipedia.com/page/Velocity_Minecraft_proxy_server)

### Pattern Matching & Sealed Classes
- [Modern Java Features: Records, Sealed Classes, Pattern Matching](https://www.javacodegeeks.com/2025/12/modern-java-language-features-records-sealed-classes-pattern-matching.html)
- [JEP 441: Pattern Matching for switch](https://openjdk.org/jeps/441)
- [Pattern Matching for switch (Oracle docs)](https://docs.oracle.com/en/java/javase/21/language/pattern-matching-switch.html)
