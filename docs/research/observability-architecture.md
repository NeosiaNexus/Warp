# Architecture d'observabilite pour Warp Proxy

**Date** : 31 mars 2026
**Objectif** : Concevoir le systeme d'observabilite le plus complet de l'ecosysteme MC proxy, en s'inspirant d'Envoy (gold standard), HAProxy, et des pratiques cloud-native modernes.
**Sources** : Documentation Envoy, Prometheus, OpenTelemetry, Micrometer, HAProxy Runtime API, JFR, Google SRE Book

---

## PHILOSOPHIE

> "You can't fix what you can't see." -- Envoy Proxy

Warp doit etre **le proxy MC le plus facile a operer en production**. Cela signifie :

1. **Zero-config par defaut** -- L'observabilite de base fonctionne sans aucune configuration
2. **Profondeur progressive** -- Du simple health check au distributed tracing complet
3. **Standards ouverts** -- Prometheus, OpenTelemetry, JFR -- jamais de format proprietaire
4. **Overhead negligeable** -- Les metriques sur le hot path (blind forwarding) coutent < 1% CPU
5. **Cardinalite maitrisee** -- Jamais de label unbounded (pas d'UUID joueur comme label Prometheus)

---

## TABLE DES MATIERES

1. [Metriques Prometheus (Micrometer)](#1-metriques-prometheus-micrometer)
2. [Admin API HTTP](#2-admin-api-http)
3. [Health Checking](#3-health-checking)
4. [Structured Logging](#4-structured-logging)
5. [Distributed Tracing (OpenTelemetry)](#5-distributed-tracing-opentelemetry)
6. [Java Flight Recorder (JFR)](#6-java-flight-recorder-jfr)
7. [SLOs et Alerting](#7-slos-et-alerting)
8. [Matrice de classification](#8-matrice-de-classification)

---

## 1. METRIQUES PROMETHEUS (Micrometer)

### 1.1 Choix technologique : Micrometer

**Micrometer** est la facade de metriques standard de l'ecosysteme Java (equivalent SLF4J pour le logging). Avantages :

- **Vendor-neutral** : Prometheus, Datadog, InfluxDB, New Relic, CloudWatch via un seul API
- **Binders pre-construits** : JVM (GC, memoire, threads), Netty (channels, ByteBuf allocator, event loops), connection pools
- **Histogrammes server-side** : Supporte les histogrammes Prometheus (aggregeables) ET les summaries client-side
- **Observation API** : API unifiee metriques + traces + logs (depuis Micrometer 1.10)
- **Overhead minimal** : Compteurs atomiques, pas de lock sur le hot path

**Alternative rejetee** : Prometheus client_java directement -- trop bas niveau, pas de binders, pas de facade multi-backend.

### 1.2 Conventions de nommage

Suivre strictement les conventions Prometheus :

```
warp_<sous_systeme>_<nom>_<unite>

Exemples :
warp_connections_total              -- counter
warp_connections_active             -- gauge
warp_connection_duration_seconds    -- histogram
warp_backend_switch_duration_seconds -- histogram
warp_packets_forwarded_total        -- counter
warp_packets_forwarded_bytes        -- counter
```

**Regles** :
- Prefixe `warp_` sur toutes les metriques applicatives
- Unites en base : `seconds` (pas ms), `bytes` (pas KB)
- `_total` pour les counters, `_seconds` pour les durees, `_bytes` pour les tailles
- snake_case exclusivement
- Les metriques JVM utilisent le prefixe standard `jvm_` (via Micrometer binders)

### 1.3 Catalogue de metriques

#### 1.3.1 Downstream (connexions entrantes) -- Inspire d'Envoy

| Metrique | Type | Labels | Description |
|----------|------|--------|-------------|
| `warp_connections_total` | Counter | `listener`, `result` | Nombre total de connexions recues |
| `warp_connections_active` | Gauge | `listener` | Connexions actuellement actives |
| `warp_connections_duration_seconds` | Histogram | `listener` | Duree de vie des connexions (de connect a disconnect) |
| `warp_handshake_duration_seconds` | Histogram | `listener`, `result` | Duree du handshake MC (SYN -> Login Success) |
| `warp_auth_duration_seconds` | Histogram | `result` | Duree de l'authentification Mojang |
| `warp_downstream_bytes_total` | Counter | `listener`, `direction` | Bytes recus/envoyes vers les clients |
| `warp_downstream_packets_total` | Counter | `listener`, `direction` | Paquets recus/envoyes vers les clients |
| `warp_ping_requests_total` | Counter | `listener`, `cached` | Requetes de ping (status), avec/sans cache |
| `warp_rate_limited_total` | Counter | `listener`, `reason` | Connexions refusees par rate limiting |

#### 1.3.2 Upstream (connexions backends) -- Inspire d'Envoy

| Metrique | Type | Labels | Description |
|----------|------|--------|-------------|
| `warp_backend_connections_total` | Counter | `backend`, `result` | Connexions tentees vers les backends |
| `warp_backend_connections_active` | Gauge | `backend` | Connexions actives par backend |
| `warp_backend_connect_duration_seconds` | Histogram | `backend` | Temps pour etablir la connexion TCP vers un backend |
| `warp_backend_handshake_duration_seconds` | Histogram | `backend` | Temps du handshake MC complet avec le backend |
| `warp_backend_upstream_bytes_total` | Counter | `backend`, `direction` | Bytes envoyes/recus depuis les backends |
| `warp_backend_upstream_packets_total` | Counter | `backend`, `direction` | Paquets envoyes/recus depuis les backends |
| `warp_backend_errors_total` | Counter | `backend`, `error_type` | Erreurs par backend (`timeout`, `refused`, `reset`, `protocol`) |
| `warp_backend_health_status` | Gauge | `backend` | Statut sante : 1=healthy, 0.5=degraded, 0=unhealthy |

#### 1.3.3 Server Switch (specifique proxy MC)

| Metrique | Type | Labels | Description |
|----------|------|--------|-------------|
| `warp_switch_total` | Counter | `from`, `to`, `result` | Nombre de server switches tentes |
| `warp_switch_duration_seconds` | Histogram | `from`, `to` | Duree complete du switch (deconnexion ancien -> connexion nouveau -> PLAY state) |
| `warp_switch_config_phase_duration_seconds` | Histogram | -- | Duree de la phase CONFIGURATION pendant le switch |
| `warp_switch_queue_size` | Gauge | -- | Paquets en file d'attente pendant un switch en cours |

#### 1.3.4 Blind Forwarding (hot path)

| Metrique | Type | Labels | Description |
|----------|------|--------|-------------|
| `warp_forwarding_packets_total` | Counter | `mode` | Paquets forwarded (`blind` vs `decoded`) |
| `warp_forwarding_bytes_total` | Counter | `mode` | Bytes forwarded par mode |
| `warp_forwarding_ratio` | Gauge | -- | Ratio blind/total (cible : >90% en PLAY state) |

#### 1.3.5 Drain System

| Metrique | Type | Labels | Description |
|----------|------|--------|-------------|
| `warp_drain_active` | Gauge | -- | 1 si en mode drain, 0 sinon |
| `warp_drain_phase` | Gauge | -- | Phase actuelle du drain (0=none, 1=soft, 2=hard) |
| `warp_drain_players_remaining` | Gauge | -- | Joueurs restants pendant le drain |
| `warp_drain_transfers_total` | Counter | `result` | Transferts effectues pendant le drain |
| `warp_drain_duration_seconds` | Histogram | -- | Duree totale du drain (du declenchement a la completion) |

#### 1.3.6 Serveur (instance Warp)

| Metrique | Type | Labels | Description |
|----------|------|--------|-------------|
| `warp_info` | Gauge | `version`, `java_version`, `os` | Metrique info (toujours 1) |
| `warp_uptime_seconds` | Gauge | -- | Uptime du proxy |
| `warp_players_online` | Gauge | -- | Joueurs connectes (global) |
| `warp_event_dispatch_duration_seconds` | Histogram | `event_type` | Duree du dispatch d'evenements aux plugins |
| `warp_plugin_errors_total` | Counter | `plugin`, `event_type` | Exceptions non-catchees dans les handlers de plugins |
| `warp_config_reloads_total` | Counter | `result` | Rechargements de configuration |

#### 1.3.7 JVM et Netty (via Micrometer Binders)

Actives automatiquement, prefixe standard :

| Binder | Metriques cles |
|--------|----------------|
| `JvmMemoryMetrics` | `jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `jvm_buffer_memory_used_bytes` (direct buffers Netty!) |
| `JvmGcMetrics` | `jvm_gc_pause_seconds`, `jvm_gc_memory_promoted_bytes` |
| `JvmThreadMetrics` | `jvm_threads_live`, `jvm_threads_daemon`, `jvm_threads_states` |
| `ProcessorMetrics` | `system_cpu_usage`, `process_cpu_usage` |
| `UptimeMetrics` | `process_uptime_seconds`, `process_start_time_seconds` |
| `NettyMetrics` (custom) | `warp_netty_channels_active`, `warp_netty_bytebuf_allocator_used_bytes`, `warp_netty_eventloop_pending_tasks` |

### 1.4 Gestion de la cardinalite

**Danger #1 en metriques** : l'explosion de cardinalite. Un proxy MC avec 10 000 joueurs et des labels `player_uuid` genererait des millions de time series.

**Regles strictes** :

1. **Jamais** de label `player`, `uuid`, `ip` ou `session_id` dans les metriques Prometheus
2. Les labels `backend` sont bornes (nombre fini de serveurs)
3. Les labels `listener` sont bornes (1-3 listeners typiquement)
4. Le label `error_type` utilise une enum : `timeout`, `refused`, `reset`, `protocol`, `other`
5. Le label `result` utilise une enum : `success`, `failure`, `timeout`
6. **Estimation cible** : < 5 000 time series pour un proxy avec 20 backends

**Pour les metriques par joueur** : Utiliser les traces OpenTelemetry ou les events JFR (pas Prometheus).

### 1.5 Histogrammes vs Summaries

**Decision : Histogrammes Prometheus exclusivement.**

Justification (alignee avec la recommandation officielle Prometheus) :
- Les histogrammes sont **aggregeables** entre instances (crucial pour le multi-proxy futur)
- Le calcul des percentiles se fait cote serveur (`histogram_quantile()`)
- Les summaries client-side ne peuvent PAS etre aggreges entre instances

**Buckets par defaut pour les latences** :
```java
// Optimises pour un proxy MC
// Handshake : 10ms - 5s
// Server switch : 50ms - 30s
// Connection duration : 1s - 24h

double[] LATENCY_BUCKETS = {
    0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0
};

double[] DURATION_BUCKETS = {
    1, 5, 15, 30, 60, 300, 900, 1800, 3600, 7200, 14400, 43200, 86400
};
```

### 1.6 Endpoint d'exposition

```
GET /metrics              -- Format Prometheus (text/plain; version=0.0.4)
GET /metrics?format=json  -- Format JSON (pour debug/admin API)
```

**Implementation** : Micrometer `PrometheusMeterRegistry` expose via un handler HTTP minimal (pas besoin de framework web).

**Classification** : **CORE** -- Active par defaut, endpoint `/metrics` toujours disponible.

---

## 2. ADMIN API HTTP

### 2.1 Philosophie

Inspiree d'Envoy et HAProxy, l'Admin API est une interface HTTP legere pour :
- **Observer** : metriques, connexions, backends, configuration
- **Operer** : drain, health check control, log levels, backend management
- **Debugger** : stats, config dump, connexions actives

**Principes de design** (alignes avec Envoy) :
- `GET` pour les requetes en lecture
- `POST` pour les mutations
- JSON par defaut, Prometheus pour `/metrics`
- Bind sur `127.0.0.1:9901` par defaut (securite)
- Path filtering configurable (comme Envoy `allow_paths`)

### 2.2 Catalogue d'endpoints

#### 2.2.1 Statut et info

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/` | Page d'accueil HTML avec navigation |
| GET | `/ready` | Readiness probe (200 si pret, 503 sinon) |
| GET | `/healthz` | Liveness probe (200 si event loops OK) |
| GET | `/info` | JSON : version, uptime, java version, config, etat |
| GET | `/help` | Liste de tous les endpoints disponibles |

#### 2.2.2 Metriques et statistiques

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/metrics` | Metriques Prometheus |
| GET | `/metrics?format=json` | Metriques en JSON |
| GET | `/stats` | Statistiques internes (connexions, throughput) en texte |
| GET | `/stats?filter=<regex>` | Filtrer les stats par regex |
| GET | `/stats?format=json` | Stats en JSON |
| GET | `/stats?usedonly` | Uniquement les stats avec activite (comme Envoy) |

#### 2.2.3 Joueurs et connexions

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/players` | Liste des joueurs connectes (UUID, backend, IP, duree, etat) |
| GET | `/players?backend=<name>` | Joueurs d'un backend specifique |
| GET | `/players/<uuid>` | Details d'un joueur (trace ID, events, metriques) |
| POST | `/players/<uuid>/kick` | Kick un joueur avec raison |
| POST | `/players/<uuid>/switch` | Forcer un switch de serveur |

#### 2.2.4 Backends

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/backends` | Liste des backends (nom, adresse, sante, joueurs, latence) |
| GET | `/backends/<name>` | Details d'un backend |
| POST | `/backends/<name>/drain` | Mettre un backend en mode drain (evacuer les joueurs) |
| POST | `/backends/<name>/disable` | Desactiver un backend (plus de nouvelles connexions) |
| POST | `/backends/<name>/enable` | Reactiver un backend |
| POST | `/backends` | Ajouter un backend dynamiquement (JSON body) |
| DELETE | `/backends/<name>` | Retirer un backend (doit etre vide) |
| POST | `/backends/<name>/health/fail` | Forcer le health check a echouer (comme Envoy) |
| POST | `/backends/<name>/health/ok` | Restaurer le health check |

#### 2.2.5 Drain et operations

| Methode | Path | Description |
|---------|------|-------------|
| POST | `/drain` | Declencher le drain du proxy (soft -> hard) |
| POST | `/drain?graceful` | Drain gracieux avec timeout configurable |
| GET | `/drain/status` | Etat du drain (phase, joueurs restants, temps ecoule) |
| POST | `/drain/cancel` | Annuler un drain en cours |

#### 2.2.6 Configuration

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/config` | Dump de la configuration active (comme Envoy `/config_dump`) |
| POST | `/config/reload` | Recharger la configuration depuis le fichier |
| GET | `/listeners` | Liste des listeners et leur etat |

#### 2.2.7 Logging

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/logging` | Niveaux de log actuels par logger |
| POST | `/logging?level=<level>` | Changer le niveau global |
| POST | `/logging?name=<logger>&level=<level>` | Changer le niveau d'un logger specifique |

#### 2.2.8 Profiling et debug

| Methode | Path | Description |
|---------|------|-------------|
| GET | `/memory` | Utilisation memoire (heap, direct buffers, Netty allocator) |
| POST | `/jfr/start` | Demarrer un enregistrement JFR avec duree |
| POST | `/jfr/stop` | Stopper et telecharger l'enregistrement JFR |
| GET | `/jfr/dump` | Telecharger le JFR courant (.jfr) |
| POST | `/threads` | Thread dump complet |

#### 2.2.9 Controle du serveur

| Methode | Path | Description |
|---------|------|-------------|
| POST | `/shutdown` | Arret propre (equivalent `drain` + arret apres completion) |
| POST | `/logs/reopen` | Rotation des fichiers de log (comme Envoy `/reopen_logs`) |

### 2.3 Securite de l'Admin API

Inspiree des bonnes pratiques d'Envoy :

1. **Bind local uniquement** par defaut (`127.0.0.1:9901`)
2. **allow_paths** configurable : restreindre les endpoints accessibles
3. **Authentification optionnelle** : bearer token configurable
4. **Audit log** : chaque mutation est loguee avec timestamp, source IP, action
5. **Pas d'exposition sur le port de jeu** (25565) -- toujours un port separe

```yaml
# warp.yml
admin:
  bind: "127.0.0.1:9901"
  # Optionnel : restreindre les paths accessibles
  allow-paths:
    - "/ready"
    - "/healthz"
    - "/metrics"
    - "/stats"
  # Optionnel : token d'authentification
  auth-token: "${WARP_ADMIN_TOKEN}"
```

**Classification** : **CORE** -- L'Admin API fait partie du binaire. Les endpoints `/ready`, `/healthz`, `/metrics` sont toujours actifs. Les endpoints de mutation sont proteges par configuration.

---

## 3. HEALTH CHECKING

### 3.1 Probes Kubernetes

Le proxy expose trois endpoints HTTP pour les probes Kubernetes :

#### 3.1.1 Startup Probe (`/ready` pendant le demarrage)

**But** : Indiquer que le proxy a termine son initialisation.

**Conditions de succes** :
- Configuration chargee et validee
- Listeners bindes et en ecoute
- Au moins un backend connecte et healthy
- Plugins charges

**Configuration K8s recommandee** :
```yaml
startupProbe:
  httpGet:
    path: /ready
    port: 9901
  failureThreshold: 30
  periodSeconds: 1        # Check chaque seconde
  # Max 30s pour demarrer
```

#### 3.1.2 Liveness Probe (`/healthz`)

**But** : Detecter si le proxy est bloque (event loop morte, deadlock).

**Conditions de succes** (verifications legeres) :
- Event loops Netty repondent (watchdog < 5s)
- Pas de deadlock detecte
- Le processus n'est PAS en mode zombi

**Ce que la liveness NE verifie PAS** (bonne pratique K8s) :
- Sante des backends (c'est le role de readiness)
- Connexion aux services externes
- Espace disque

```yaml
livenessProbe:
  httpGet:
    path: /healthz
    port: 9901
  initialDelaySeconds: 0    # La startup probe couvre le demarrage
  periodSeconds: 10
  failureThreshold: 3       # 3 echecs = restart
  timeoutSeconds: 2
```

#### 3.1.3 Readiness Probe (`/ready`)

**But** : Indiquer si le proxy peut accepter de nouvelles connexions.

**Conditions de succes** :
- Liveness OK
- Au moins N backends healthy (configurable, defaut : 1)
- Pas en mode drain
- Pas de surcharge (optionnel : check du nombre de connexions actives vs max)

**En mode drain** : `/ready` retourne 503, ce qui signale au load balancer d'arreter d'envoyer du trafic.

```yaml
readinessProbe:
  httpGet:
    path: /ready
    port: 9901
  periodSeconds: 5
  failureThreshold: 1       # 1 echec = retrait immediat du pool
  successThreshold: 2       # 2 succes pour revenir dans le pool
```

### 3.2 Health checking des backends

Inspire directement d'Envoy, avec deux strategies complementaires :

#### 3.2.1 Active Health Checking

Le proxy envoie periodiquement des requetes aux backends pour verifier leur sante.

**Types de checks** :

| Type | Methode | Description |
|------|---------|-------------|
| **TCP** | Connexion TCP | Verifie que le port est ouvert. Rapide, minimal. |
| **MC Ping** | Status Request MC | Envoie un Handshake + Status Request. Verifie la reponse MOTD. Plus couteux mais plus precis. |
| **MC Login** | Connexion complete | Se connecte en mode offline avec un joueur fantome. Verifie que le serveur accepte les connexions. Le plus precis, le plus couteux. |

**Configuration** :
```yaml
backends:
  lobby:
    address: "localhost:25566"
    health-check:
      type: mc-ping          # tcp | mc-ping | mc-login
      interval: 10s          # Intervalle entre les checks
      timeout: 5s            # Timeout par check
      healthy-threshold: 2   # Succes consecutifs pour passer healthy
      unhealthy-threshold: 3 # Echecs consecutifs pour passer unhealthy
```

#### 3.2.2 Passive Health Checking (Outlier Detection)

Inspire d'Envoy : utiliser les vraies connexions joueurs pour detecter les backends defaillants.

**Signaux passifs** :
- Taux d'erreur de connexion > seuil (ex: 50% sur 30s)
- Latence de handshake > seuil (ex: P99 > 5s)
- Nombre de kicks immediats apres connexion > seuil
- Connexions TCP refusees ou reset

**Ejection** :
- Un backend qui depasse les seuils est ejected du pool de load balancing
- Duree d'ejection progressive (30s, 60s, 120s, ...) -- comme Envoy
- Reintegration automatique si le check actif reussit

```yaml
backends:
  lobby:
    outlier-detection:
      enabled: true
      consecutive-errors: 5         # Erreurs consecutives avant ejection
      success-rate-threshold: 85    # % minimum de succes
      ejection-time: 30s            # Duree initiale d'ejection
      max-ejection-percent: 50      # Jamais ejecter plus de 50% des backends
```

#### 3.2.3 Etats de sante

Modele a 3 etats (inspire d'Envoy) :

| Etat | Signification | Comportement du LB |
|------|---------------|---------------------|
| **HEALTHY** | Le backend repond normalement | Recoit du trafic normal |
| **DEGRADED** | Le backend repond mais lentement | Recoit du trafic seulement si pas assez de healthy |
| **UNHEALTHY** | Le backend ne repond pas | Retire du pool de LB |

```
warp_backend_health_status{backend="lobby"} 1.0    -- HEALTHY
warp_backend_health_status{backend="survival"} 0.5 -- DEGRADED
warp_backend_health_status{backend="creative"} 0.0 -- UNHEALTHY
```

### 3.3 Integration avec le drain system

Quand un backend signale qu'il drain (via un flag dans la reponse MC Ping, ou via l'Admin API) :

1. Le backend passe en etat DRAINING (pas de nouvelles connexions)
2. Les joueurs existants continuent normalement
3. Le proxy peut declencher des Transfer Packets pour evacuer les joueurs
4. Une fois le backend vide, il passe en UNHEALTHY
5. Le backend peut etre retire en toute securite

**Classification** : **CORE** pour les probes K8s et le health checking TCP. **OPTIONAL** (config) pour le MC Ping, MC Login, et l'outlier detection.

---

## 4. STRUCTURED LOGGING

### 4.1 Choix technologique : SLF4J 2.0 + Logback

**SLF4J 2.0** comme facade (standard industriel, compatible avec tous les frameworks).
**Logback** comme implementation, pour les raisons suivantes :

- Performance superieure en synchrone (1.6x plus rapide que Log4j2 en sync)
- Log4j2 est meilleur en async multi-thread, mais pour un proxy Netty, les I/O sont deja asynchrones -- le logging n'est pas le goulot
- Logback est le defaut de l'ecosysteme (Spring Boot, Quarkus, Micronaut)
- Support natif du format ECS et Logstash
- MDC pour le contexte par connexion

**SLF4J 2.0 Fluent API** pour le structured logging :
```java
logger.atInfo()
    .addKeyValue("player_uuid", player.uuid())
    .addKeyValue("backend", backend.name())
    .addKeyValue("latency_ms", duration.toMillis())
    .log("Player connected to backend");
```

### 4.2 Format de sortie

#### 4.2.1 Mode developpement (defaut en TTY)

Format humain lisible, colore :
```
2026-03-31 14:30:00.123 INFO  [conn-1234] Player connected -- uuid=abc-123 backend=lobby latency=45ms
```

#### 4.2.2 Mode production (defaut si pas TTY, ou configurable)

Format JSON structuire, compatible ECS (Elastic Common Schema) :
```json
{
  "@timestamp": "2026-03-31T14:30:00.123Z",
  "log.level": "INFO",
  "message": "Player connected",
  "service.name": "warp-proxy",
  "service.version": "1.0.0",
  "trace.id": "abc123def456",
  "span.id": "789ghi",
  "warp.player.uuid": "550e8400-e29b-41d4-a716-446655440000",
  "warp.player.name": "Steve",
  "warp.backend": "lobby",
  "warp.listener": "default",
  "warp.connection.id": "conn-1234",
  "warp.latency_ms": 45,
  "event.module": "warp.connection"
}
```

**Pourquoi ECS** :
- Standard ouvert de l'ecosysteme Elastic
- Noms de champs uniformes cross-services
- Correlation automatique avec les traces OpenTelemetry (`trace.id`, `span.id`)
- Compatible avec Loki, Datadog, Splunk (pas seulement Elasticsearch)

### 4.3 Contexte par connexion (MDC)

Chaque connexion joueur injecte un contexte dans le MDC (Mapped Diagnostic Context) :

```java
// A la connexion
MDC.put("connection_id", connection.id());
MDC.put("player_uuid", player.uuid().toString());
MDC.put("player_name", player.name());
MDC.put("remote_ip", connection.remoteAddress().toString());
MDC.put("listener", listener.name());
MDC.put("trace_id", span.getSpanContext().getTraceId());

// Au switch de backend
MDC.put("backend", backend.name());

// A la deconnexion
MDC.clear();
```

**Probleme avec les virtual threads et Netty** : Le MDC est base sur `ThreadLocal`. Avec les event loops Netty et les virtual threads, un meme thread peut servir plusieurs connexions.

**Solution** : Contexte scope a la connexion, propage manuellement dans les handlers Netty et les virtual threads.

```java
// ConnectionContext -- immutable, attache au Channel
public record ConnectionContext(
    String connectionId,
    @Nullable UUID playerUuid,
    @Nullable String playerName,
    @Nullable String backend,
    String remoteIp,
    String listener,
    @Nullable String traceId
) {
    // Applique au MDC pour la duree d'un handler
    public void applyToMDC() { ... }
    public void clearFromMDC() { ... }
}

// Dans le pipeline Netty
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    var context = ctx.channel().attr(CONNECTION_CONTEXT).get();
    context.applyToMDC();
    try {
        // ... traitement
    } finally {
        context.clearFromMDC();
    }
}
```

### 4.4 Niveaux de log et strategie

| Niveau | Usage dans Warp | Volume attendu |
|--------|----------------|----------------|
| **ERROR** | Erreurs non-recuperables : crash de plugin, corruption de paquet, OOM | Tres faible (alertable) |
| **WARN** | Situations anormales mais gerees : timeout backend, rate limit, handshake echoue | Faible |
| **INFO** | Evenements operationnels : demarrage, arret, rechargement config, drain, connexion/deconnexion joueur | Modere |
| **DEBUG** | Details de fonctionnement : routing decisions, health check results, event dispatch | Eleve (desactive en prod) |
| **TRACE** | Dump de paquets, contenu des handshakes, flow complet d'une connexion | Tres eleve (debug uniquement) |

### 4.5 Log sampling sous charge

Quand le proxy est sous charge (ex: attaque DDoS, flood de ping), certains logs peuvent etre tres volumineux. Solution :

```java
// Rate-limited logger pour les events frequents
private static final RateLimitedLogger RATE_LIMITED =
    new RateLimitedLogger(logger, Duration.ofSeconds(10));

// Au lieu de logger chaque connexion rate-limited :
RATE_LIMITED.warn("Rate limiting active, {} connections rejected in last 10s", count);
```

**Implementation** : Un wrapper SLF4J qui utilise un token bucket pour limiter les logs par message template.

### 4.6 Configuration

```yaml
logging:
  format: auto          # auto (detecte TTY) | json | text
  level: INFO           # Niveau global
  levels:               # Niveaux par package
    warp.protocol: WARN
    warp.health: DEBUG
    warp.plugins: INFO
  file: logs/warp.log   # Fichier (en plus de stdout)
  rotation:
    max-size: 100MB
    max-history: 7       # Jours
    total-size-cap: 1GB
  sampling:
    enabled: true
    rate: 100            # Max 100 logs/seconde pour les messages repetitifs
```

**Classification** : **CORE** -- Le logging est toujours actif. Le format JSON est **OPTIONAL** (config). Le log sampling est **CORE** (protection contre le flood).

---

## 5. DISTRIBUTED TRACING (OpenTelemetry)

### 5.1 Vue d'ensemble

Le distributed tracing permet de suivre le parcours complet d'un joueur a travers le proxy, du connect au disconnect. C'est le seul moyen d'avoir des metriques **par joueur** sans exploser la cardinalite Prometheus.

**Choix : OpenTelemetry SDK for Java**
- Standard CNCF, vendor-neutral
- Exporte vers Jaeger, Tempo, Zipkin, Datadog, etc.
- API legere, SDK optionnel
- Supporte W3C TraceContext pour la propagation

### 5.2 Design des spans

Chaque trace represente la session complete d'un joueur. Les spans representent les phases et operations :

```
Trace: Player Session (550e8400-e29b-41d4-a716-446655440000)
|
|-- Span: TCP Connection (duration: 4h 23m)
|   |-- Attribute: remote_ip=192.168.1.42
|   |-- Attribute: listener=default
|   |
|   |-- Span: Handshake (12ms)
|   |   |-- Attribute: protocol_version=769
|   |   |-- Attribute: server_address=play.example.com
|   |
|   |-- Span: Authentication (340ms)
|   |   |-- Attribute: auth_method=mojang
|   |   |-- Attribute: player_name=Steve
|   |   |-- Event: auth_success
|   |
|   |-- Span: Initial Route (85ms)
|   |   |-- Attribute: backend=lobby
|   |   |-- Attribute: routing_rule=default
|   |   |-- Span: Backend Connect (23ms)
|   |   |-- Span: Backend Handshake (62ms)
|   |
|   |-- Span: Play Session [lobby] (2h 15m)
|   |   |-- Event: chat_message (x142)
|   |   |-- Event: plugin_message (x38)
|   |
|   |-- Span: Server Switch [lobby -> survival] (180ms)
|   |   |-- Span: Disconnect from lobby (15ms)
|   |   |-- Span: Configuration Phase (45ms)
|   |   |-- Span: Connect to survival (120ms)
|   |   |-- Event: switch_complete
|   |
|   |-- Span: Play Session [survival] (2h 8m)
|   |
|   |-- Event: disconnect (reason=quit)
```

### 5.3 Attributs standards

Definis comme constantes semantiques :

```java
public final class WarpTraceAttributes {
    // Joueur
    public static final AttributeKey<String> PLAYER_UUID = stringKey("warp.player.uuid");
    public static final AttributeKey<String> PLAYER_NAME = stringKey("warp.player.name");
    public static final AttributeKey<String> PLAYER_IP = stringKey("warp.player.ip");

    // Connexion
    public static final AttributeKey<String> CONNECTION_ID = stringKey("warp.connection.id");
    public static final AttributeKey<Long> PROTOCOL_VERSION = longKey("warp.protocol.version");
    public static final AttributeKey<String> LISTENER = stringKey("warp.listener");

    // Backend
    public static final AttributeKey<String> BACKEND = stringKey("warp.backend");
    public static final AttributeKey<String> BACKEND_ADDRESS = stringKey("warp.backend.address");

    // Routing
    public static final AttributeKey<String> ROUTING_RULE = stringKey("warp.routing.rule");
    public static final AttributeKey<String> FORWARDING_MODE = stringKey("warp.forwarding.mode");

    // Switch
    public static final AttributeKey<String> SWITCH_FROM = stringKey("warp.switch.from");
    public static final AttributeKey<String> SWITCH_TO = stringKey("warp.switch.to");
    public static final AttributeKey<String> SWITCH_REASON = stringKey("warp.switch.reason");
}
```

### 5.4 Context propagation vers les backends

Le proxy peut propager le contexte de trace vers les backends MC via :

1. **Plugin Messages** : Envoyer le `traceparent` W3C dans un canal de plugin message custom (`warp:trace`)
2. **Forwarding Headers** : Injecter dans les donnees de forwarding (extension du modern forwarding)
3. **Canal headless** : Si le canal proxy-backend existe, propager le contexte dedans

Cela permet aux backends (Paper + plugin Warp) de lier leurs spans au meme trace.

### 5.5 Sampling

En production avec des milliers de joueurs, tracer CHAQUE session serait trop couteux. Strategies de sampling :

| Strategie | Description | Quand |
|-----------|-------------|-------|
| **Always-off** | Pas de tracing | Defaut (opt-in) |
| **Ratio-based** | Tracer X% des sessions | Production normale |
| **Error-biased** | Tracer 100% des sessions avec erreurs | Toujours |
| **Tail-based** | Decision apres la session (si erreur ou latence anormale) | Avance (via OTel Collector) |

```yaml
tracing:
  enabled: false          # Desactive par defaut (opt-in)
  exporter: otlp          # otlp | jaeger | zipkin | none
  endpoint: "http://localhost:4317"  # OTel Collector
  sampling:
    strategy: ratio       # always-on | always-off | ratio | error-biased
    ratio: 0.01           # 1% des sessions
  propagation: w3c        # w3c | b3 | jaeger
```

### 5.6 Integration avec Micrometer Observation API

Micrometer 1.10+ fournit une `Observation API` qui unifie metriques et traces :

```java
// Un seul appel genere a la fois une metrique et un span
Observation.createNotStarted("warp.server.switch", registry)
    .lowCardinalityKeyValue("from", fromBackend)
    .lowCardinalityKeyValue("to", toBackend)
    .highCardinalityKeyValue("player", playerUuid)  // Uniquement dans le span, pas la metrique
    .observe(() -> performSwitch());
```

Cela evite la duplication code entre metriques et traces.

**Classification** : **OPTIONAL** -- Le tracing est desactive par defaut et active via configuration. L'API OpenTelemetry est incluse (zero-overhead quand inactive grace au no-op SDK). L'export necessite un OTel Collector externe.

---

## 6. JAVA FLIGHT RECORDER (JFR)

### 6.1 Pourquoi JFR en plus de Prometheus et OTel

JFR comble un creneau unique :
- **Overhead < 1%** en production (concu par Oracle pour le always-on)
- **Donnees par evenement** sans limite de cardinalite (contrairement a Prometheus)
- **Stack traces** sur chaque evenement (impossible avec Prometheus)
- **Profiling integre** : CPU, allocations, I/O, locks, GC -- sans outil externe
- **Self-contained** : Pas besoin de backend (fichier .jfr local)
- **Post-mortem** : Le fichier .jfr persiste apres un crash

### 6.2 Custom JFR Events pour Warp

Definir des events JFR custom pour chaque operation proxy critique :

```java
// Evenement de connexion joueur
@Label("Player Connection")
@Category({"Warp", "Connection"})
@Description("A player connected to the proxy")
@StackTrace(false)  // Pas besoin de stack trace pour les connexions
public class PlayerConnectionEvent extends jdk.jfr.Event {
    @Label("Player UUID")
    public String playerUuid;

    @Label("Player Name")
    public String playerName;

    @Label("Remote IP")
    public String remoteIp;

    @Label("Backend")
    public String backend;

    @Label("Listener")
    public String listener;

    @Label("Auth Duration (ms)")
    @Timespan(Timespan.MILLISECONDS)
    public long authDurationMs;
}

// Evenement de server switch
@Label("Server Switch")
@Category({"Warp", "Routing"})
@Description("A player switched between backend servers")
public class ServerSwitchEvent extends jdk.jfr.Event {
    @Label("Player UUID")
    public String playerUuid;

    @Label("From Backend")
    public String fromBackend;

    @Label("To Backend")
    public String toBackend;

    @Label("Switch Duration (ms)")
    @Timespan(Timespan.MILLISECONDS)
    public long switchDurationMs;

    @Label("Reason")
    public String reason;

    @Label("Success")
    public boolean success;
}

// Evenement de forwarding de paquet (duration event)
@Label("Packet Forward")
@Category({"Warp", "Protocol"})
@Description("A packet was forwarded between client and backend")
@Threshold("1 ms")  // Ne pas enregistrer les paquets forwarded en < 1ms
@StackTrace(false)
public class PacketForwardEvent extends jdk.jfr.Event {
    @Label("Packet ID")
    public int packetId;

    @Label("Direction")
    public String direction;  // client_to_backend | backend_to_client

    @Label("Size (bytes)")
    @DataAmount
    public long sizeBytes;

    @Label("Mode")
    public String mode;  // blind | decoded
}

// Evenement de health check
@Label("Health Check")
@Category({"Warp", "Health"})
public class HealthCheckEvent extends jdk.jfr.Event {
    @Label("Backend")
    public String backend;

    @Label("Type")
    public String type;  // tcp | mc-ping | mc-login

    @Label("Duration (ms)")
    @Timespan(Timespan.MILLISECONDS)
    public long durationMs;

    @Label("Result")
    public String result;  // healthy | degraded | unhealthy | timeout
}

// Evenement de drain
@Label("Drain Phase")
@Category({"Warp", "Operations"})
public class DrainEvent extends jdk.jfr.Event {
    @Label("Phase")
    public String phase;  // started | soft | hard | completed | cancelled

    @Label("Players Remaining")
    public int playersRemaining;

    @Label("Transfers Completed")
    public int transfersCompleted;
}

// Evenement d'erreur plugin
@Label("Plugin Error")
@Category({"Warp", "Plugins"})
@StackTrace(true)  // Stack trace important pour le debug
public class PluginErrorEvent extends jdk.jfr.Event {
    @Label("Plugin")
    public String plugin;

    @Label("Event Type")
    public String eventType;

    @Label("Error Message")
    public String errorMessage;
}
```

### 6.3 Utilisation dans le code

```java
// Zero-overhead quand JFR est inactif (le JIT eliminate le code)
var event = new ServerSwitchEvent();
event.begin();

// ... effectuer le switch ...

event.playerUuid = player.uuid().toString();
event.fromBackend = fromBackend.name();
event.toBackend = toBackend.name();
event.switchDurationMs = duration.toMillis();
event.reason = reason;
event.success = success;
event.end();

if (event.shouldCommit()) {
    event.commit();
}
```

### 6.4 JFR Event Streaming (Java 14+)

Exporter les events JFR en temps reel vers Prometheus/Grafana via le streaming API :

```java
// Streamer JFR -> Prometheus (dans un thread dedie)
try (var stream = new RecordingStream()) {
    stream.enable("warp.ServerSwitch").withThreshold(Duration.ZERO);
    stream.enable("warp.PlayerConnection");

    stream.onEvent("warp.ServerSwitch", event -> {
        switchDurationHistogram.record(
            event.getDuration().toMillis() / 1000.0,
            Tags.of(
                "from", event.getString("fromBackend"),
                "to", event.getString("toBackend")
            )
        );
    });

    stream.startAsync();
}
```

Cela permet d'alimenter les metriques Prometheus DEPUIS JFR, sans double instrumentation.

### 6.5 Configuration JFR

```yaml
jfr:
  enabled: true            # Always-on par defaut
  settings: default        # default | profile | custom
  repository: jfr/         # Dossier pour les enregistrements
  max-age: 1h              # Retention des enregistrements
  max-size: 100MB          # Taille max du repository
  dump-on-exit: true       # Sauvegarder un .jfr a l'arret
  streaming:
    enabled: false          # Export temps reel vers Prometheus
```

**Classification** : **CORE** -- JFR est toujours actif par defaut (overhead < 1%). Le streaming vers Prometheus est **OPTIONAL** (config).

---

## 7. SLOS ET ALERTING

### 7.1 SLOs recommandes pour un proxy MC

Les SLOs sont definis en termes de **SLI** (Service Level Indicator) avec un **objectif** :

#### 7.1.1 Disponibilite

| SLI | Formule | Objectif | Fenetre |
|-----|---------|----------|---------|
| **Connection success rate** | `connexions_reussies / connexions_tentees` | 99.9% | 30 jours |
| **Availability** | `minutes_ready / minutes_totales` (probe readiness) | 99.95% | 30 jours |

**Regles PromQL** :
```promql
# SLI : Connection success rate (fentere 30j)
sum(rate(warp_connections_total{result="success"}[5m]))
/
sum(rate(warp_connections_total[5m]))

# Error budget remaining
1 - (
  (1 - (sum(increase(warp_connections_total{result="success"}[30d]))
        / sum(increase(warp_connections_total[30d]))))
  / (1 - 0.999)
)
```

#### 7.1.2 Latence

| SLI | Formule | Objectif | Fenetre |
|-----|---------|----------|---------|
| **Handshake latency P99** | `histogram_quantile(0.99, warp_handshake_duration_seconds)` | < 500ms | 30 jours |
| **Server switch latency P99** | `histogram_quantile(0.99, warp_switch_duration_seconds)` | < 2s | 30 jours |
| **Auth latency P99** | `histogram_quantile(0.99, warp_auth_duration_seconds)` | < 1s | 30 jours |

**Important** : Formuler les SLIs en proportion plutot qu'en percentile brut (best practice Google SRE) :
```promql
# "99% des connexions ont un handshake < 500ms"
sum(rate(warp_handshake_duration_seconds_bucket{le="0.5"}[5m]))
/
sum(rate(warp_handshake_duration_seconds_count[5m]))
```

#### 7.1.3 Operations

| SLI | Formule | Objectif | Fenetre |
|-----|---------|----------|---------|
| **Drain completion** | `drains_completes_dans_le_temps / drains_totaux` | 99% en < 5min | 30 jours |
| **Config reload success** | `reloads_success / reloads_total` | 100% | 30 jours |
| **Backend health flaps** | transitions unhealthy/healthy par heure | < 5/heure | 1 heure |

### 7.2 Strategie d'alerting

Approche **burn rate** (Google SRE) plutot que seuils statiques :

#### 7.2.1 Alertes critiques (page on-call)

| Alerte | Condition | Fenetre | Severite |
|--------|-----------|---------|----------|
| **High error rate** | Burn rate 14x sur 1h (error budget consomme en ~2j) | 1h | CRITICAL |
| **All backends down** | 0 backends healthy | Immediat | CRITICAL |
| **Proxy not ready** | `/ready` retourne 503 pendant > 2min | 2min | CRITICAL |
| **Event loop stuck** | `/healthz` timeout pendant > 30s | 30s | CRITICAL |
| **OOM imminent** | `jvm_memory_used_bytes` > 90% du max | 5min | CRITICAL |

#### 7.2.2 Alertes warning (ticket)

| Alerte | Condition | Fenetre | Severite |
|--------|-----------|---------|----------|
| **Elevated error rate** | Burn rate 3x sur 6h (error budget consomme en ~10j) | 6h | WARNING |
| **High switch latency** | P99 switch > 5s pendant 15min | 15min | WARNING |
| **Backend degraded** | Un backend en etat DEGRADED depuis > 10min | 10min | WARNING |
| **Drain stalled** | Drain en cours depuis > 10min avec joueurs restants | 10min | WARNING |
| **Plugin errors** | > 10 erreurs de plugins par minute | 5min | WARNING |
| **Direct buffer growth** | `jvm_buffer_memory_used_bytes` en croissance continue sur 1h | 1h | WARNING |
| **Connection leak** | `warp_connections_active` ne diminue pas malgre deconnexions | 30min | WARNING |

### 7.3 Dashboards Grafana recommandes

**Dashboard 1 : Vue d'ensemble (operateur)**
- Joueurs online (gauge)
- Connexions/seconde (rate)
- Backends : statut sante, joueurs, latence
- Error rate (graph)
- Etat du drain

**Dashboard 2 : Performance (SRE)**
- Handshake latency (heatmap)
- Server switch latency (heatmap)
- Blind forwarding ratio
- Packets/seconde et bytes/seconde
- Event dispatch duration

**Dashboard 3 : JVM (debug)**
- Heap usage
- Direct buffer usage (Netty)
- GC pauses
- Thread count
- CPU usage
- Netty event loop pending tasks

**Dashboard 4 : Backends (ops)**
- Par backend : joueurs, connexions, erreurs, latence
- Health check results timeline
- Outlier detection ejections
- Trafic par backend (graph stacked)

**Classification** : **PLUGIN** -- Warp fournit les metriques et la documentation des SLOs. Les regles d'alerting et dashboards Grafana sont fournis comme templates (fichiers JSON/YAML dans le repo), pas integres au binaire.

---

## 8. MATRICE DE CLASSIFICATION

### 8.1 Tableau recapitulatif

| Fonctionnalite | Classification | Justification |
|----------------|---------------|---------------|
| **Metriques Prometheus** (`/metrics`) | **CORE** | Toujours actif, endpoint expose par defaut |
| **Micrometer binders JVM/Netty** | **CORE** | Enregistres automatiquement au demarrage |
| **Admin API** (`/ready`, `/healthz`, `/info`, `/stats`) | **CORE** | Necessaire pour operer le proxy |
| **Admin API mutations** (`/drain`, `/backends`, `/logging`) | **CORE** | Proteges par auth, mais dans le binaire |
| **Health checking TCP backends** | **CORE** | Verifie que les backends sont joignables |
| **Health checking MC Ping** | **OPTIONAL** | Active par config (`health-check.type: mc-ping`) |
| **Health checking MC Login** | **OPTIONAL** | Active par config (`health-check.type: mc-login`) |
| **Outlier Detection** | **OPTIONAL** | Active par config (`outlier-detection.enabled: true`) |
| **Structured Logging (texte)** | **CORE** | Toujours actif, format auto-detecte |
| **Structured Logging (JSON/ECS)** | **OPTIONAL** | Active par config (`logging.format: json`) |
| **Log sampling** | **CORE** | Protection automatique contre le flood |
| **MDC context par connexion** | **CORE** | Toujours enrichi, visible dans tous les logs |
| **JFR custom events** | **CORE** | Always-on, overhead < 1% |
| **JFR event streaming** | **OPTIONAL** | Active par config (`jfr.streaming.enabled: true`) |
| **JFR dump via Admin API** | **CORE** | Toujours accessible (`/jfr/dump`) |
| **OpenTelemetry tracing** | **OPTIONAL** | Desactive par defaut, active par config |
| **OTel context propagation backends** | **OPTIONAL** | Necessite plugin cote backend |
| **SLO recording rules** | **PLUGIN** | Templates Prometheus fournis dans le repo |
| **Alerting rules** | **PLUGIN** | Templates fournis, deployes par l'operateur |
| **Dashboards Grafana** | **PLUGIN** | JSON fournis dans le repo, importables |
| **Custom metrics plugin API** | **PLUGIN** | Les plugins peuvent enregistrer leurs propres metriques via Micrometer |
| **Custom JFR events plugin API** | **PLUGIN** | Les plugins peuvent definir leurs propres events JFR |
| **Custom health checks plugin** | **PLUGIN** | Les plugins peuvent ajouter des health checks custom |

### 8.2 Dependances

```
CORE (zero-config, toujours actif)
├── Micrometer Core + Prometheus Registry
├── SLF4J 2.0 + Logback
├── JDK Flight Recorder (fourni par le JDK)
└── HTTP server minimal (Admin API)

OPTIONAL (active par config)
├── OpenTelemetry SDK + OTLP Exporter
├── Logback JSON Encoder (ECS)
└── JFR RecordingStream (streaming)

PLUGIN (extensible via API)
├── MeterRegistry expose aux plugins
├── JFR Event base class exposee aux plugins
└── HealthCheck interface exposee aux plugins
```

### 8.3 Schema d'architecture

```
                                       +-----------------+
                                       |  Grafana        |
                                       |  Dashboards     |
                                       +-------+---------+
                                               |
                              +----------------+----------------+
                              |                                 |
                      +-------v--------+              +---------v-------+
                      |  Prometheus    |              |  Tempo/Jaeger   |
                      |  (scrape)     |              |  (traces)       |
                      +-------+--------+              +---------+-------+
                              |                                 |
                              | GET /metrics                    | OTLP gRPC
                              |                                 |
+-----------------------------+-----+---+-----------------------+-------+
|                           WARP PROXY                                  |
|                                                                       |
|  +------------------+  +------------------+  +-------------------+    |
|  | Micrometer       |  | OpenTelemetry    |  | JFR               |    |
|  | Registry         |  | SDK (optional)   |  | (always-on)       |    |
|  |                  |  |                  |  |                   |    |
|  | Counters         |  | Spans            |  | Custom Events     |    |
|  | Gauges           |  | Context Prop.    |  | CPU/Alloc/GC      |    |
|  | Histograms       |  | Attributes       |  | Stack Traces      |    |
|  +--------+---------+  +--------+---------+  +--------+----------+    |
|           |                     |                     |               |
|           +---------------------+---------------------+               |
|                                 |                                     |
|                    +------------v-----------+                         |
|                    | Observation API        |                         |
|                    | (metriques + traces    |                         |
|                    |  unifiees)             |                         |
|                    +------------------------+                         |
|                                                                       |
|  +------------------+  +------------------+  +-------------------+    |
|  | SLF4J + Logback  |  | Admin API HTTP   |  | Health Checking   |    |
|  | Structured Logs  |  | :9901            |  |                   |    |
|  | MDC Context      |  | /ready /healthz  |  | Active (TCP/Ping) |    |
|  | JSON/ECS/Text    |  | /metrics /stats  |  | Passive (Outlier) |    |
|  |                  |  | /drain /backends |  | K8s Probes        |    |
|  +------------------+  +------------------+  +-------------------+    |
|                                                                       |
+-----------------------------------------------------------------------+
                              |
                    +---------+---------+
                    |  Loki / ELK       |
                    |  (logs)           |
                    +-------------------+
```

---

## 9. COMPARAISON AVEC L'EXISTANT

| Fonctionnalite | Velocity | BungeeCord | Gate | Warp (propose) |
|----------------|----------|------------|------|----------------|
| Metriques Prometheus | Plugin tiers | Non | Natif | **CORE** |
| OpenTelemetry | Non | Non | Natif | **OPTIONAL** |
| Admin API | Non | Non | Partiel | **CORE** (complet, inspire Envoy) |
| Health probes K8s | Non | Non | Oui | **CORE** |
| Health check backends | Basique | Basique | Oui | **CORE** (TCP + Ping + Outlier) |
| Structured Logging JSON | Non | Non | Oui | **OPTIONAL** |
| JFR custom events | Non | Non | Non (Go) | **CORE** |
| Log context par connexion | Non | Non | Partiel | **CORE** |
| Drain observabilite | N/A (pas de drain) | N/A | Partiel | **CORE** |
| SLO templates | Non | Non | Non | **PLUGIN** (templates fournis) |
| Dashboards Grafana | Non | Non | Non | **PLUGIN** (templates fournis) |
| Plugin metrics API | Non | Non | Non | **PLUGIN** |

---

## 10. PLAN D'IMPLEMENTATION

### Phase 3 (Cloud-Native) -- Priorite haute

1. **Micrometer + Prometheus endpoint** (`/metrics`)
   - Counters/gauges de base : connexions, joueurs, backends
   - Binders JVM (memoire, GC, threads)
   - Binders Netty (channels, allocator, event loops)

2. **Admin API minimale**
   - `/ready`, `/healthz`, `/info`, `/help`
   - `/stats`, `/metrics`
   - `/players`, `/backends`

3. **Health checking TCP backends**
   - Check TCP basique avec intervalle configurable
   - Integration avec le load balancer

4. **Structured logging**
   - SLF4J 2.0 + Logback
   - MDC context par connexion
   - Auto-detection TTY pour le format

5. **JFR custom events**
   - PlayerConnectionEvent, ServerSwitchEvent, HealthCheckEvent
   - Always-on par defaut

### Phase 5 (Production Polish) -- Priorite moyenne

6. **Admin API complete**
   - `/drain`, `/backends/<name>/drain`, `/backends/<name>/enable|disable`
   - `/logging` (runtime log level change)
   - `/jfr/dump`, `/jfr/start`, `/jfr/stop`

7. **Health checking avance**
   - MC Ping health check
   - Outlier detection (passive)
   - Etats DEGRADED

8. **OpenTelemetry tracing** (OPTIONAL)
   - Spans pour le parcours joueur
   - OTLP exporter
   - Sampling configurable

9. **JSON/ECS logging** (OPTIONAL)
   - Logback JSON encoder
   - Format ECS

10. **SLO/Dashboard templates**
    - Recording rules Prometheus
    - Alerting rules
    - Dashboards Grafana JSON

---

## SOURCES

### Envoy Proxy
- [Envoy Statistics Architecture](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/observability/statistics.html)
- [Envoy Administration Interface](https://www.envoyproxy.io/docs/envoy/latest/operations/admin.html)
- [Envoy Health Checking](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/health_checking)
- [Envoy Outlier Detection](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/outlier)
- [Envoy Gateway Proxy Metrics](https://gateway.envoyproxy.io/contributions/design/proxy-metrics/)
- [Proxy Observability Scenarios with Envoy Gateway](https://tetrate.io/blog/proxy-observability-scenarios-with-envoy-gateway)

### Prometheus
- [Metric and label naming](https://prometheus.io/docs/practices/naming/)
- [Histograms and summaries](https://prometheus.io/docs/practices/histograms/)
- [Prometheus Best Practices: 8 Dos and Don'ts](https://betterstack.com/community/guides/monitoring/prometheus-best-practices/)
- [Prometheus metric naming recommendations - Chronosphere](https://docs.chronosphere.io/ingest/metrics-traces/collector/mappings/prometheus/prometheus-recommendations)
- [Prometheus Label Best Practices](https://oneuptime.com/blog/post/2026-01-30-prometheus-label-best-practices/view)

### OpenTelemetry
- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
- [Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)
- [Traces](https://opentelemetry.io/docs/concepts/signals/traces/)
- [Improving Platform Observability with Distributed Tracing - JAVAPRO](https://javapro.io/2025/09/22/improving-platform-observability-with-distributed-tracing-and-opentelemetry/)
- [Observability Beyond Monitoring: OpenTelemetry - Java Code Geeks](https://www.javacodegeeks.com/2026/02/observability-beyond-monitoring-opentelemetry-and-distributed-tracing.html)
- [OpenTelemetry with Spring Boot](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/)

### Micrometer
- [Micrometer JVM Metrics](https://docs.micrometer.io/micrometer/reference/reference/jvm.html)
- [Micrometer Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)
- [Micrometer Observation API - Java Code Geeks](https://www.javacodegeeks.com/2025/11/micrometers-observation-api-unified-observability-for-the-jvm.html)
- [Instrumenting Java Apps with Prometheus Metrics](https://betterstack.com/community/guides/monitoring/java-prometheus/)

### Structured Logging
- [Structured Logging Best Practices - Uptrace](https://uptrace.dev/glossary/structured-logging)
- [ECS Logging in Java - Macro Nepal](https://macronepal.com/2025/11/27/ecs-logging-in-java-structured-logging-with-elastic-common-schema/blog/)
- [Structured Logging in Spring Boot 3.4](https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4/)
- [Java Logging with MDC - Baeldung](https://www.baeldung.com/mdc-in-log4j-2-logback)

### JFR
- [Monitoring REST APIs with Custom JDK Flight Recorder Events - Gunnar Morling](https://www.morling.dev/blog/rest-api-monitoring-with-custom-jdk-flight-recorder-events/)
- [Custom JDK Flight Recorder Events - Inside.java](https://inside.java/2022/04/25/sip48/)
- [Monitoring Java Applications with Flight Recorder - Baeldung](https://www.baeldung.com/java-flight-recorder-monitoring)
- [Using JDK Flight Recorder - Quarkus](https://quarkus.io/guides/jfr)

### Health Checking & Kubernetes
- [Configure Liveness, Readiness and Startup Probes - Kubernetes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes Health Checks and Probes - Better Stack](https://betterstack.com/community/guides/monitoring/kubernetes-health-checks/)
- [Readiness vs liveliness probes - Google Cloud Blog](https://cloud.google.com/blog/products/containers-kubernetes/kubernetes-best-practices-setting-up-health-checks-with-readiness-and-liveness-probes)

### SLOs & Alerting
- [Google SRE - Service Level Objectives](https://sre.google/sre-book/service-level-objectives/)
- [How to Include Latency in SLO-Based Alerting - Grafana Labs](https://grafana.com/blog/2019/11/27/kubecon-recap-how-to-include-latency-in-slo-based-alerting/)
- [Best practices for Grafana SLOs](https://grafana.com/docs/grafana-cloud/alerting-and-irm/slo/best-practices/)
- [What is P95 latency - SRE School](https://sreschool.com/blog/p95-latency/)

### HAProxy
- [HAProxy Runtime API](https://www.haproxy.com/documentation/haproxy-runtime-api/)
- [Dynamic configuration with the HAProxy Runtime API](https://www.haproxy.com/blog/dynamic-configuration-haproxy-runtime-api)
- [HAProxy Prometheus metrics](https://www.haproxy.com/documentation/haproxy-configuration-tutorials/alerts-and-monitoring/prometheus/)

### Logging Frameworks
- [The 6 Best Java Logging Frameworks in 2025 - Dash0](https://www.dash0.com/faq/the-6-best-java-logging-frameworks-in-2025-a-comprehensive-guide)
- [SLF4J, Logback, and Log4j - Sergio Lema](https://sergiolema.dev/2025/08/25/slf4j-logback-and-log4j-a-straightforward-guide-to-java-logging/)
- [Benchmarking synchronous and asynchronous logging - Logback](https://logback.qos.ch/performance.html)
