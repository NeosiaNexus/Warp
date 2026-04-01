# Architecture Cloud-Native Kubernetes pour Warp Proxy

**Date** : 31 mars 2026
**Objectif** : Faire de Warp le proxy Minecraft le plus cloud-native jamais construit. Couvrir Agones, service discovery, autoscaling, deploiement, networking, Helm, service mesh, Docker, et les patterns de production.
**Sources** : Documentation Agones, Kubernetes, KEDA, Shulker, Cilium, Argo Rollouts, Prometheus, recherche academique sur les service meshes, forums Hypixel, retours communautaires.

---

## TABLE DES MATIERES

1. [Agones : Orchestration de game servers](#1-agones--orchestration-de-game-servers)
2. [Service Discovery](#2-service-discovery)
3. [Autoscaling](#3-autoscaling)
4. [Patterns de deploiement](#4-patterns-de-deploiement)
5. [Networking Kubernetes](#5-networking-kubernetes)
6. [Helm Chart](#6-helm-chart)
7. [Deploiements MC a grande echelle](#7-deploiements-mc-a-grande-echelle)
8. [Docker : bonnes pratiques Java 21](#8-docker--bonnes-pratiques-java-21)
9. [Service Mesh](#9-service-mesh)
10. [Architecture cible pour Warp](#10-architecture-cible-pour-warp)

---

## 1. AGONES : ORCHESTRATION DE GAME SERVERS

### 1.1 Vue d'ensemble

Agones est un projet open-source (Google for Games + Ubisoft) qui fournit des CRDs Kubernetes pour gerer des serveurs de jeu dedies. Il est le standard de facto pour orchestrer des game servers sur K8s.

**Concepts cles** :
- **GameServer** : CRD representant un processus de serveur de jeu unique dans un Pod
- **Fleet** : Collection de GameServers identiques (equivalent a un ReplicaSet)
- **FleetAutoscaler** : Autoscaling des Fleets (buffer, webhook, counter, list)
- **GameServerAllocation** : Reservation atomique d'un GameServer pour un joueur/groupe

**Etats d'un GameServer** :
```
PortAllocation -> Creating -> Starting -> Scheduled -> RequestReady -> Ready -> Allocated -> Shutdown
```

Le SDK sidecar communique avec le controller via gRPC (port 9357) et HTTP (port 9358).

### 1.2 Integration avec un proxy MC

Dans l'ecosysteme Minecraft, le **proxy** (Warp) est le point d'entree joueur, et les **backends** sont des GameServers Agones. Le proxy doit :

1. **Decouvrir** les GameServers `Ready` ou `Allocated` via l'API K8s ou Agones
2. **Allouer** un GameServer quand un joueur veut rejoindre un mode de jeu (`GameServerAllocation`)
3. **Router** le joueur vers le GameServer alloue
4. **Reagir** aux changements d'etat (GameServer shutdown, crash, drain)

**Architecture recommandee** :

```
[Joueurs] --> [LoadBalancer] --> [Warp Proxy (Deployment)]
                                       |
                                       ├── Watch K8s API (Endpoints / Agones CRDs)
                                       ├── GameServerAllocation (via API K8s)
                                       └── Routage direct vers GameServer Pods
                                              |
                                    [Agones Fleet: Lobby]
                                    [Agones Fleet: SkyWars]
                                    [Agones Fleet: BedWars]
```

### 1.3 GameServer YAML pour un serveur MC

```yaml
apiVersion: "agones.dev/v1"
kind: GameServer
metadata:
  generateName: "mc-lobby-"
  labels:
    game: minecraft
    mode: lobby
spec:
  # Ports : le serveur MC ecoute sur 25565
  ports:
    - name: mc
      portPolicy: Dynamic    # Agones attribue un port hote dynamique
      containerPort: 25565
      protocol: TCP
  # Health checking : Agones ping le SDK sidecar
  health:
    initialDelaySeconds: 30   # Le serveur MC met du temps a demarrer
    periodSeconds: 12
    failureThreshold: 5
  # SDK server (sidecar injecte par Agones)
  sdkServer:
    logLevel: Info
    grpcPort: 9357
    httpPort: 9358
  # Compteurs et listes (Beta) -- essentiel pour MC
  counters:
    players:
      count: 0
      capacity: 100          # Max joueurs sur ce serveur
  lists:
    playerIds:
      values: []
  # Template du Pod
  template:
    metadata:
      labels:
        game: minecraft
        mode: lobby
    spec:
      containers:
        - name: mc-server
          # Image Minestom/Paper/custom
          image: ghcr.io/example/mc-lobby:1.21.4
          resources:
            requests:
              cpu: "1"
              memory: "2Gi"
            limits:
              cpu: "2"
              memory: "3Gi"
          env:
            - name: JAVA_OPTS
              value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
```

### 1.4 Fleet YAML

```yaml
apiVersion: "agones.dev/v1"
kind: Fleet
metadata:
  name: mc-skywars
  labels:
    game: minecraft
    mode: skywars
spec:
  # Nombre de GameServers Ready en attente
  replicas: 5
  # Packed = regrouper sur un minimum de nœuds (optimise le cout cloud)
  scheduling: Packed
  # Strategie de mise a jour
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 25%
  # Labels ajoutes aux GameServers Allocated (overflow)
  allocationOverflow:
    labels:
      version: "1.21.4-build42"
  # Priorites d'allocation
  priorities:
    - type: Counter
      key: players
      order: Ascending       # Remplir les serveurs les moins charges d'abord
  # Template du GameServer (identique a ci-dessus)
  template:
    metadata:
      labels:
        game: minecraft
        mode: skywars
    spec:
      ports:
        - name: mc
          portPolicy: Dynamic
          containerPort: 25565
          protocol: TCP
      health:
        initialDelaySeconds: 30
        periodSeconds: 12
        failureThreshold: 5
      counters:
        players:
          count: 0
          capacity: 16        # SkyWars = 16 joueurs max
      template:
        spec:
          containers:
            - name: mc-server
              image: ghcr.io/example/mc-skywars:1.21.4
              resources:
                requests:
                  cpu: "500m"
                  memory: "1Gi"
                limits:
                  cpu: "1"
                  memory: "2Gi"
```

### 1.5 FleetAutoscaler YAML

```yaml
# Buffer policy : garder toujours N serveurs Ready
apiVersion: "autoscaling.agones.dev/v1"
kind: FleetAutoscaler
metadata:
  name: mc-skywars-autoscaler
spec:
  fleetName: mc-skywars
  policy:
    type: Buffer
    buffer:
      bufferSize: 3          # 3 serveurs Ready en permanence
      minReplicas: 2
      maxReplicas: 50
  sync:
    type: FixedInterval
    fixedInterval:
      seconds: 15            # Verification toutes les 15s
---
# Counter policy : scaler en fonction du nombre de joueurs
apiVersion: "autoscaling.agones.dev/v1"
kind: FleetAutoscaler
metadata:
  name: mc-lobby-autoscaler
spec:
  fleetName: mc-lobby
  policy:
    type: Counter
    counter:
      key: players
      bufferSize: 50          # Garder 50 places libres
      minCapacity: 100
      maxCapacity: 5000
---
# Webhook policy : logique custom (ex: horaire, evenement special)
apiVersion: "autoscaling.agones.dev/v1"
kind: FleetAutoscaler
metadata:
  name: mc-custom-autoscaler
spec:
  fleetName: mc-events
  policy:
    type: Webhook
    webhook:
      service:
        name: scaling-webhook
        namespace: minecraft
        path: /scale
      caBundle: "<base64-encoded-ca>"
  sync:
    type: FixedInterval
    fixedInterval:
      seconds: 30
```

### 1.6 GameServerAllocation YAML

```yaml
# Demande d'allocation : le proxy appelle ca quand un joueur veut jouer a SkyWars
apiVersion: "allocation.agones.dev/v1"
kind: GameServerAllocation
spec:
  # Selecteurs ordonnes : essayer d'abord les serveurs pas pleins
  selectors:
    - matchLabels:
        agones.dev/fleet: mc-skywars
      gameServerState: Allocated    # Serveurs deja en jeu mais pas pleins
      counters:
        players:
          minAvailable: 1           # Au moins 1 place libre
    - matchLabels:
        agones.dev/fleet: mc-skywars
      gameServerState: Ready        # Sinon, prendre un serveur neuf
  # Scheduling : regrouper les joueurs
  scheduling: Packed
  # Metadata ajoutee a l'allocation
  metadata:
    labels:
      allocated: "true"
  # Incrementer le compteur de joueurs a l'allocation
  counters:
    players:
      action: Increment
      amount: 1
  # Priorites : remplir les serveurs avec le plus de joueurs d'abord
  priorities:
    - type: Counter
      key: players
      order: Descending
```

**Dans le code Warp**, l'allocation se fait via un appel REST a l'API K8s :
```
POST /apis/allocation.agones.dev/v1/namespaces/minecraft/gameserverallocations
```

La reponse contient l'IP et le port du GameServer alloue, que Warp utilise pour router le joueur.

---

## 2. SERVICE DISCOVERY

### 2.1 Strategies comparees

| Methode | Latence de decouverte | Complexite | Use case |
|---------|----------------------|------------|----------|
| **K8s Endpoints Watch** | Temps reel (~100ms) | Moyenne | Backends fixes (lobbies) |
| **Headless Service + DNS** | Dependant du TTL DNS | Faible | Simple, peu de backends |
| **Agones CRD Watch** | Temps reel | Moyenne | GameServers dynamiques |
| **Agones Allocator** | A la demande | Faible | Allocation de match/partie |
| **Custom CRD** | Temps reel | Elevee | Metadata riche, logique custom |

### 2.2 K8s Endpoints Watch (recommande pour le proxy)

Le pattern le plus efficace pour Warp est de **watch les Endpoints** des services headless representant les backends.

```yaml
# Service headless pour decouvrir les lobbies
apiVersion: v1
kind: Service
metadata:
  name: mc-lobby
  namespace: minecraft
spec:
  clusterIP: None              # Headless !
  selector:
    game: minecraft
    mode: lobby
  ports:
    - name: mc
      port: 25565
      targetPort: 25565
```

**Dans Warp** (pseudo-code Java utilisant le client K8s fabric8) :
```java
// Watch des endpoints pour mise a jour en temps reel
kubernetesClient.endpoints()
    .inNamespace("minecraft")
    .withName("mc-lobby")
    .watch(new Watcher<Endpoints>() {
        @Override
        public void eventReceived(Action action, Endpoints endpoints) {
            // Mettre a jour la liste des backends dans le router
            List<BackendAddress> addresses = endpoints.getSubsets().stream()
                .flatMap(s -> s.getAddresses().stream())
                .map(a -> new BackendAddress(a.getIp(), 25565))
                .toList();
            router.updateBackends("lobby", addresses);
        }
    });
```

**Alternative moderne** : `EndpointSlices` (recommande depuis K8s 1.21+) au lieu de `Endpoints`. Les EndpointSlices scalent mieux (chunks de 100 endpoints max) et supportent le dual-stack IPv4/IPv6.

### 2.3 Agones CRD Watch

Pour les backends Agones, watcher directement les `GameServer` CRDs :

```java
// Watch des GameServers Agones
// Necessite les CRDs Agones et les RBAC appropriees
dynamicClient.resource(gameServerCRD)
    .inNamespace("minecraft")
    .withLabel("mode", "skywars")
    .watch(new Watcher<GenericKubernetesResource>() {
        @Override
        public void eventReceived(Action action, GenericKubernetesResource gs) {
            String state = gs.get("status", "state"); // Ready, Allocated, Shutdown
            String address = gs.get("status", "address");
            int port = gs.get("status", "ports", 0, "port");
            // Mettre a jour selon l'etat
        }
    });
```

### 2.4 Pattern hybride recommande pour Warp

Warp doit supporter les deux modes, configurable :

```yaml
# warp.yaml - Configuration de service discovery
service-discovery:
  # Mode 1 : Kubernetes natif (Endpoints/EndpointSlices)
  kubernetes:
    enabled: true
    namespace: minecraft
    watch-labels:
      game: minecraft
    resync-interval: 30s

  # Mode 2 : Agones GameServer CRDs
  agones:
    enabled: false
    namespace: minecraft
    fleet-selector:
      game: minecraft

  # Mode 3 : Statique (fallback, dev, non-K8s)
  static:
    enabled: false
    servers:
      lobby:
        address: 10.0.0.1:25565
```

---

## 3. AUTOSCALING

### 3.1 HPA avec metriques custom Prometheus

Le proxy Warp expose `warp_players_online` via Prometheus. On peut scaler le proxy lui-meme en fonction de la charge.

```yaml
# HPA base sur les metriques Prometheus de Warp
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: warp-proxy-hpa
  namespace: minecraft
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: warp-proxy
  minReplicas: 2
  maxReplicas: 10
  behavior:
    scaleDown:
      # Descente lente : attendre que le drain soit termine
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1             # Max 1 pod supprime a la fois
          periodSeconds: 120   # Toutes les 2 minutes
    scaleUp:
      # Montee rapide en cas d'afflux
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 30
  metrics:
    - type: Pods
      pods:
        metric:
          name: warp_players_online
        target:
          type: AverageValue
          averageValue: "5000"   # 5000 joueurs par instance proxy
```

**Prerequis** : Prometheus Adapter ou KEDA pour exposer les metriques custom au HPA.

### 3.2 KEDA : Autoscaling event-driven

KEDA est plus puissant que le HPA natif pour notre cas : il supporte le scale-to-zero (pour les backends), les triggers Prometheus natifs, et une configuration plus expressive.

```yaml
# KEDA ScaledObject pour le proxy Warp
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: warp-proxy-scaler
  namespace: minecraft
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: warp-proxy
  pollingInterval: 15           # Verifier toutes les 15s
  cooldownPeriod: 300           # 5min avant de scale down
  minReplicaCount: 2            # Jamais moins de 2 (HA)
  maxReplicaCount: 20
  fallback:
    failureThreshold: 3
    replicas: 4                 # Si Prometheus est down, garder 4 replicas
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleDown:
          stabilizationWindowSeconds: 300
          policies:
            - type: Pods
              value: 1
              periodSeconds: 180
  triggers:
    # Trigger 1 : Nombre de joueurs par instance
    - type: prometheus
      metadata:
        serverAddress: http://prometheus.monitoring:9090
        query: |
          sum(warp_players_online) / count(up{job="warp-proxy"})
        threshold: "5000"
        activationThreshold: "100"
    # Trigger 2 : Taux de connexion entrant
    - type: prometheus
      metadata:
        serverAddress: http://prometheus.monitoring:9090
        query: |
          sum(rate(warp_connections_total{result="success"}[5m]))
        threshold: "200"
---
# KEDA pour scale-to-zero des backends MC (pas le proxy !)
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: mc-creative-scaler
  namespace: minecraft
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: mc-creative
  pollingInterval: 30
  cooldownPeriod: 600            # 10min sans joueur avant shutdown
  idleReplicaCount: 0            # Scale to zero !
  minReplicaCount: 0
  maxReplicaCount: 5
  triggers:
    - type: prometheus
      metadata:
        serverAddress: http://prometheus.monitoring:9090
        query: |
          sum(mc_players_online{server="creative"}) or vector(0)
        threshold: "1"
        activationThreshold: "1"
```

### 3.3 Scale-down avec drain

Le probleme critique : quand K8s veut supprimer un Pod proxy, il y a des joueurs connectes. On ne peut PAS juste kill le Pod.

**Solution : Drain system integre a Warp** :

1. K8s envoie SIGTERM au Pod proxy
2. Le `preStop` hook declenche le drain via l'Admin API
3. Warp passe en mode drain : refuse les nouvelles connexions, transfere les joueurs existants
4. Une fois vide, le processus se termine proprement

```yaml
# Extrait du Deployment Warp montrant le lifecycle
spec:
  terminationGracePeriodSeconds: 3600    # 1h max pour drainer
  containers:
    - name: warp
      lifecycle:
        preStop:
          httpGet:
            path: /drain/start
            port: 9901               # Admin API
      readinessProbe:
        httpGet:
          path: /ready
          port: 9901
        periodSeconds: 5
        failureThreshold: 1
      livenessProbe:
        httpGet:
          path: /health/live
          port: 9901
        initialDelaySeconds: 10
        periodSeconds: 10
        failureThreshold: 6
```

**Sequence complete** :
```
1. K8s decide de supprimer le Pod (scale down, node drain, update)
2. Pod marque "Terminating"
3. preStop hook: POST /drain/start
4. Warp readiness probe -> 503 (retire du Service = plus de nouveaux joueurs)
5. Warp transfere les joueurs vers d'autres instances Warp
6. warp_drain_players_remaining -> 0
7. Warp quitte proprement (exit 0)
8. K8s supprime le Pod
```

### 3.4 PodDisruptionBudget

Le PDB est essentiel : il empeche K8s de supprimer trop de Pods proxy simultanement (ex: pendant un `kubectl drain` ou un upgrade de cluster).

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: warp-proxy-pdb
  namespace: minecraft
spec:
  # Au minimum 80% des replicas doivent rester disponibles
  minAvailable: "80%"
  selector:
    matchLabels:
      app: warp-proxy
---
# Alternative pour les backends MC : tolerer 1 serveur indisponible a la fois
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: mc-lobby-pdb
  namespace: minecraft
spec:
  maxUnavailable: 1
  selector:
    matchLabels:
      mode: lobby
```

**Regle importante** : Le PDB ne s'applique PAS aux rolling updates d'un Deployment. Il ne protege que contre les disruptions *volontaires* (node drain, cluster upgrade). Le RollingUpdate strategy du Deployment gere les updates lui-meme.

---

## 4. PATTERNS DE DEPLOIEMENT

### 4.1 Deployment vs StatefulSet pour le proxy

| Critere | Deployment | StatefulSet |
|---------|-----------|-------------|
| **Identite stable** | Non (noms aleatoires) | Oui (warp-0, warp-1...) |
| **Stockage persistant** | Non necessaire | PVC par Pod |
| **Mise a jour** | Parallele, rapide | Sequentielle (pod par pod) |
| **Scale down** | N'importe quel Pod | Dernier Pod d'abord |
| **Reseau stable** | Non | DNS stable par Pod |
| **Use case proxy** | **RECOMMANDE** | Non necessaire |

**Verdict pour Warp : Deployment**. Un proxy MC est *fondamentalement stateless* :
- L'etat des joueurs est en memoire volatile (OK de le perdre si on draine proprement)
- Pas besoin de stockage persistant
- Pas besoin de noms stables (les joueurs se connectent via le Service, pas via le nom du Pod)
- Les rolling updates doivent etre rapides et paralleles
- Le scale down doit pouvoir cibler n'importe quel Pod (celui avec le moins de joueurs)

**Exception** : Les *backends MC* (lobbies, serveurs de jeu) sont stateful (monde, inventaires). Pour ceux-la, StatefulSet + PVC est justifie.

### 4.2 Rolling Update avec zero-downtime

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: warp-proxy
  namespace: minecraft
  labels:
    app: warp-proxy
spec:
  replicas: 3
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      # Creer 1 nouveau Pod AVANT de supprimer un ancien
      maxSurge: 1
      # Ne jamais avoir plus de 0 Pod indisponible
      maxUnavailable: 0
  selector:
    matchLabels:
      app: warp-proxy
  template:
    metadata:
      labels:
        app: warp-proxy
        version: "1.2.0"
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9100"
        prometheus.io/path: "/metrics"
    spec:
      serviceAccountName: warp-proxy
      terminationGracePeriodSeconds: 3600
      # Anti-affinite : repartir les Pods sur differents nœuds
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: warp-proxy
                topologyKey: kubernetes.io/hostname
      containers:
        - name: warp
          image: ghcr.io/warp-mc/warp:1.2.0
          ports:
            - name: mc
              containerPort: 25577
              protocol: TCP
            - name: metrics
              containerPort: 9100
              protocol: TCP
            - name: admin
              containerPort: 9901
              protocol: TCP
          env:
            - name: JAVA_OPTS
              value: >-
                -XX:+UseContainerSupport
                -XX:MaxRAMPercentage=75.0
                -XX:+UseG1GC
                -XX:+UseStringDeduplication
                -XX:+ExitOnOutOfMemoryError
            - name: WARP_CLUSTER_ENABLED
              value: "true"
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: POD_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
          resources:
            requests:
              cpu: "2"
              memory: "4Gi"
            limits:
              cpu: "4"
              memory: "6Gi"
          readinessProbe:
            httpGet:
              path: /ready
              port: 9901
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 1
            successThreshold: 1
          livenessProbe:
            httpGet:
              path: /health/live
              port: 9901
            initialDelaySeconds: 15
            periodSeconds: 10
            failureThreshold: 6
          startupProbe:
            httpGet:
              path: /health/live
              port: 9901
            periodSeconds: 5
            failureThreshold: 30        # 150s max pour demarrer
          lifecycle:
            preStop:
              httpGet:
                path: /drain/start
                port: 9901
          volumeMounts:
            - name: config
              mountPath: /opt/warp/config
              readOnly: true
            - name: plugins
              mountPath: /opt/warp/plugins
              readOnly: true
      volumes:
        - name: config
          configMap:
            name: warp-proxy-config
        - name: plugins
          emptyDir: {}
```

**Points cles du zero-downtime** :
1. `maxSurge: 1, maxUnavailable: 0` -- le nouveau Pod est cree et Ready AVANT de supprimer l'ancien
2. `readinessProbe` sur `/ready` -- ne recoit du trafic que quand pret
3. `preStop` sur `/drain/start` -- les joueurs sont transferes avant la suppression
4. `terminationGracePeriodSeconds: 3600` -- jusqu'a 1h pour drainer les joueurs
5. `startupProbe` -- ne pas tuer un Pod qui demarre lentement
6. `podAntiAffinity` -- ne pas mettre tous les proxies sur le meme noeud

### 4.3 Canary / Blue-Green avec Argo Rollouts

Pour des mises a jour plus sophistiquees, Argo Rollouts remplace le Deployment standard :

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: warp-proxy
  namespace: minecraft
spec:
  replicas: 5
  revisionHistoryLimit: 3
  selector:
    matchLabels:
      app: warp-proxy
  strategy:
    canary:
      # Etapes progressives
      steps:
        - setWeight: 10          # 10% du trafic sur le canary
        - pause: { duration: 5m }
        - setWeight: 30
        - pause: { duration: 5m }
        - setWeight: 60
        - pause: { duration: 5m }
        # Si tout va bien, promotion automatique a 100%
      # Analyse automatique pendant le canary
      analysis:
        templates:
          - templateName: warp-canary-analysis
        startingStep: 1
        args:
          - name: service-name
            value: warp-proxy
      # Nombre max de Pods canary
      maxSurge: "20%"
      maxUnavailable: 0
  template:
    # ... identique au template du Deployment
---
# Template d'analyse : verifier les metriques pendant le canary
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: warp-canary-analysis
  namespace: minecraft
spec:
  metrics:
    # Verifier que le taux d'erreur est acceptable
    - name: error-rate
      interval: 60s
      failureLimit: 3
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(warp_backend_errors_total{pod=~".*canary.*"}[5m]))
            /
            sum(rate(warp_connections_total{pod=~".*canary.*"}[5m]))
      successCondition: result[0] < 0.01    # < 1% d'erreurs
    # Verifier la latence du handshake
    - name: handshake-latency
      interval: 60s
      failureLimit: 3
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            histogram_quantile(0.99,
              sum(rate(warp_handshake_duration_seconds_bucket{pod=~".*canary.*"}[5m])) by (le)
            )
      successCondition: result[0] < 2.0     # P99 < 2 secondes
```

**Note pour le proxy MC** : Le canary est plus complexe car les joueurs ont des connexions longues. Le "poids" du trafic s'applique aux *nouvelles connexions*, pas aux connexions existantes. C'est acceptable car les joueurs se reconnectent regulierement.

---

## 5. NETWORKING KUBERNETES

### 5.1 NodePort vs LoadBalancer

| Critere | NodePort | LoadBalancer | Ingress (TCP) |
|---------|---------|-------------|---------------|
| **Port** | 30000-32767 | Port arbitraire (25565) | Port 443/80 (ou TCP stream) |
| **IP client** | Perdue (SNAT) sauf `externalTrafficPolicy: Local` | Perdue (SNAT) sauf policy Local/PROXY protocol | Depend du controller |
| **Cout** | Gratuit | 1 LB cloud (~$15-25/mois) | 1 LB cloud |
| **DNS** | IP de nœud | IP statique du LB | IP statique |
| **Scalabilite** | Limitee par le nombre de nœuds | Excellente | Excellente |
| **Minecraft** | OK pour dev/petit | **RECOMMANDE pour prod** | Possible mais over-engineering |

**Recommandation pour Warp : `LoadBalancer` avec `externalTrafficPolicy: Local`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: warp-proxy
  namespace: minecraft
  annotations:
    # AWS NLB (si sur EKS)
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
    service.beta.kubernetes.io/aws-load-balancer-scheme: "internet-facing"
    # GCP (si sur GKE)
    cloud.google.com/l4-rbs: "enabled"
spec:
  type: LoadBalancer
  # CRITIQUE : preserver l'IP source du joueur
  externalTrafficPolicy: Local
  selector:
    app: warp-proxy
  ports:
    - name: mc
      port: 25565              # Port Minecraft standard
      targetPort: 25577        # Port du conteneur Warp
      protocol: TCP
```

### 5.2 externalTrafficPolicy: Local

**Pourquoi c'est critique** :

- **Cluster (defaut)** : kube-proxy/Cilium fait du SNAT. L'IP vue par Warp est celle d'un noeud K8s interne. On perd l'IP du joueur. Les bans IP, le rate limiting, la geolocalisation sont impossibles.
- **Local** : Le trafic reste sur le noeud qui recoit la connexion. L'IP du joueur est preservee. Mais si un noeud n'a pas de Pod Warp, le health check du LB echoue et le trafic n'est pas route vers ce noeud.

**Compromis** :
- L'equilibrage de charge est potentiellement inegal (un noeud avec 3 Pods proxy vs un avec 1)
- Solution : `podAntiAffinity` pour repartir les Pods proxy equitablement

### 5.3 PROXY Protocol v2

Alternative a `externalTrafficPolicy: Local` quand celui-ci n'est pas possible (ou en complement) :

```
[Joueur 1.2.3.4] --> [LB avec PROXY Protocol v2] --> [Warp]
                      Ajoute un header binaire:
                      PROXY TCP4 1.2.3.4 10.0.0.1 51234 25565
```

**Warp doit supporter le PROXY Protocol** (comme Velocity le fait deja). C'est un feature essentiel pour K8s.

```yaml
# warp.yaml
listeners:
  - bind: 0.0.0.0:25577
    proxy-protocol: true       # Activer le decodage PROXY protocol
```

**Attention** : Si le PROXY protocol est active cote Warp, TOUS les clients doivent envoyer le header PROXY. Une connexion directe sans LB sera rejetee. Solution : un second listener sans PROXY protocol pour le health check interne.

### 5.4 Cilium / eBPF : Le futur du networking MC

Cilium (CNI base sur eBPF) offre des avantages majeurs pour le gaming :

**Sans kube-proxy (remplacement eBPF)** :
- Supprime les regles iptables (O(n) -> O(1) via hashtables eBPF)
- Latence reduite de 30-40% sur le P99 vs iptables
- Performance constante quelle que soit la taille du cluster

**Direct Server Return (DSR)** :
- Le trafic retour (serveur -> joueur) ne repasse PAS par le load balancer
- Reduit la latence et la charge reseau de moitie sur le retour
- Preserve l'IP source nativement

**Configuration recommandee pour un cluster MC** :
```yaml
# values.yaml de Cilium (Helm)
kubeProxyReplacement: true       # Remplacer kube-proxy entierement
loadBalancer:
  mode: dsr                      # Direct Server Return
  algorithm: maglev              # Consistent hashing pour les connexions longues
  acceleration: native           # XDP acceleration
bpf:
  masquerade: true
  hostRouting: true              # Bypass iptables pour le host routing
ipam:
  mode: kubernetes
```

**Maglev consistent hashing** est particulierement important pour Minecraft : les connexions TCP sont longues (heures), et le hashing consistant garantit qu'une reconnexion apres une breve deconnexion retourne au meme Pod proxy.

### 5.5 Multi-proxy et partage de Load Balancer

Un cluster MC avec plusieurs serveurs (lobby, creative, survival) peut mututaliser un seul LB en utilisant le proxy comme routeur :

```
[DNS: play.example.com] --> [LB unique :25565] --> [Warp Proxy]
                                                       |
                                                       ├── lobby.internal (serveur lobby)
                                                       ├── creative.internal (serveur creative)
                                                       └── survival.internal (serveur survival)
```

Le joueur se connecte a `play.example.com`. Warp gere le routage interne. Un seul LB cloud = un seul cout.

---

## 6. HELM CHART

### 6.1 Structure du chart

```
charts/warp/
├── Chart.yaml
├── values.yaml
├── values.schema.json          # Schema JSON pour validation
├── templates/
│   ├── _helpers.tpl
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── serviceaccount.yaml
│   ├── rbac.yaml               # RBAC pour service discovery K8s
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── hpa.yaml
│   ├── pdb.yaml
│   ├── servicemonitor.yaml     # Pour Prometheus Operator
│   ├── prometheusrule.yaml     # Alertes Prometheus
│   └── NOTES.txt
├── ci/
│   ├── test-values.yaml
│   └── production-values.yaml
└── README.md
```

### 6.2 values.yaml complet

```yaml
# ====================================================================
# Warp Proxy Helm Chart — values.yaml
# ====================================================================

# -- Nombre de replicas (ignore si autoscaling.enabled=true)
replicaCount: 2

image:
  repository: ghcr.io/warp-mc/warp
  tag: ""                        # Defaut: Chart.appVersion
  pullPolicy: IfNotPresent

imagePullSecrets: []
nameOverride: ""
fullnameOverride: ""

# -- Configuration du ServiceAccount
serviceAccount:
  create: true
  annotations: {}
  name: ""

# -- RBAC pour service discovery K8s et Agones
rbac:
  create: true
  # Watcher les endpoints et GameServers
  rules:
    - apiGroups: [""]
      resources: ["endpoints", "services", "pods"]
      verbs: ["get", "list", "watch"]
    - apiGroups: ["discovery.k8s.io"]
      resources: ["endpointslices"]
      verbs: ["get", "list", "watch"]
    - apiGroups: ["agones.dev"]
      resources: ["gameservers", "fleets"]
      verbs: ["get", "list", "watch"]
    - apiGroups: ["allocation.agones.dev"]
      resources: ["gameserverallocations"]
      verbs: ["create"]

# -- Configuration Warp (monte dans /opt/warp/config/warp.yaml)
config:
  listeners:
    - bind: "0.0.0.0:25577"
      proxy-protocol: false
      max-connections: 10000

  admin:
    bind: "0.0.0.0:9901"
    # En K8s, l'admin API peut etre accessible depuis le cluster
    # La securisation se fait via NetworkPolicy

  metrics:
    bind: "0.0.0.0:9100"
    enabled: true

  # Service discovery K8s
  service-discovery:
    kubernetes:
      enabled: true
      namespace: "minecraft"
      watch-labels:
        game: minecraft
    agones:
      enabled: false

# -- Service Kubernetes
service:
  type: LoadBalancer
  port: 25565
  targetPort: 25577
  externalTrafficPolicy: Local
  annotations: {}
  # Exemples d'annotations par provider :
  # AWS NLB:
  #   service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
  # GCP:
  #   cloud.google.com/l4-rbs: "enabled"

# -- Ressources
resources:
  requests:
    cpu: "1"
    memory: "2Gi"
  limits:
    cpu: "4"
    memory: "4Gi"

# -- Probes
probes:
  readiness:
    httpGet:
      path: /ready
      port: admin
    periodSeconds: 5
    failureThreshold: 1
  liveness:
    httpGet:
      path: /health/live
      port: admin
    initialDelaySeconds: 15
    periodSeconds: 10
    failureThreshold: 6
  startup:
    httpGet:
      path: /health/live
      port: admin
    periodSeconds: 5
    failureThreshold: 30

# -- Lifecycle (drain)
lifecycle:
  preStop:
    httpGet:
      path: /drain/start
      port: admin
  terminationGracePeriodSeconds: 3600

# -- Autoscaling
autoscaling:
  enabled: false
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  # KEDA (alternatif au HPA standard)
  keda:
    enabled: false
    pollingInterval: 15
    cooldownPeriod: 300
    prometheus:
      serverAddress: http://prometheus.monitoring:9090
      query: "sum(warp_players_online) / count(up{job=\"warp-proxy\"})"
      threshold: "5000"

# -- PodDisruptionBudget
podDisruptionBudget:
  enabled: true
  minAvailable: "80%"

# -- Monitoring (Prometheus Operator)
serviceMonitor:
  enabled: false
  interval: 15s
  scrapeTimeout: 10s
  additionalLabels: {}
  # release: prometheus       # Label requis par beaucoup d'installations Prometheus Operator

# -- Alertes Prometheus
prometheusRule:
  enabled: false
  additionalLabels: {}
  rules:
    - alert: WarpHighErrorRate
      expr: |
        sum(rate(warp_backend_errors_total[5m]))
        / sum(rate(warp_connections_total[5m])) > 0.05
      for: 5m
      labels:
        severity: critical
      annotations:
        summary: "Taux d'erreur Warp > 5%"
    - alert: WarpDrainActive
      expr: warp_drain_active == 1
      for: 30m
      labels:
        severity: warning
      annotations:
        summary: "Warp est en mode drain depuis plus de 30 minutes"
    - alert: WarpHighHandshakeLatency
      expr: |
        histogram_quantile(0.99,
          sum(rate(warp_handshake_duration_seconds_bucket[5m])) by (le)
        ) > 5
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "P99 handshake latency > 5s"

# -- Affinite et toleration
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
              - key: app.kubernetes.io/name
                operator: In
                values:
                  - warp
          topologyKey: kubernetes.io/hostname

tolerations: []
nodeSelector: {}

# -- Java opts
javaOpts: >-
  -XX:+UseContainerSupport
  -XX:MaxRAMPercentage=75.0
  -XX:+UseG1GC
  -XX:+UseStringDeduplication
  -XX:+ExitOnOutOfMemoryError

# -- Volumes supplementaires (plugins, etc.)
extraVolumes: []
extraVolumeMounts: []
```

### 6.3 Templates essentiels

#### ServiceMonitor (Prometheus Operator)

```yaml
# templates/servicemonitor.yaml
{{- if .Values.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "warp.fullname" . }}
  labels:
    {{- include "warp.labels" . | nindent 4 }}
    {{- with .Values.serviceMonitor.additionalLabels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  selector:
    matchLabels:
      {{- include "warp.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: metrics
      interval: {{ .Values.serviceMonitor.interval }}
      scrapeTimeout: {{ .Values.serviceMonitor.scrapeTimeout }}
      path: /metrics
      honorLabels: true
  namespaceSelector:
    matchNames:
      - {{ .Release.Namespace }}
{{- end }}
```

#### PodDisruptionBudget

```yaml
# templates/pdb.yaml
{{- if .Values.podDisruptionBudget.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "warp.fullname" . }}
  labels:
    {{- include "warp.labels" . | nindent 4 }}
spec:
  {{- if .Values.podDisruptionBudget.minAvailable }}
  minAvailable: {{ .Values.podDisruptionBudget.minAvailable }}
  {{- else if .Values.podDisruptionBudget.maxUnavailable }}
  maxUnavailable: {{ .Values.podDisruptionBudget.maxUnavailable }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "warp.selectorLabels" . | nindent 6 }}
{{- end }}
```

#### HPA / KEDA

```yaml
# templates/hpa.yaml
{{- if and .Values.autoscaling.enabled (not .Values.autoscaling.keda.enabled) }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "warp.fullname" . }}
  labels:
    {{- include "warp.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "warp.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1
          periodSeconds: 120
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 30
  metrics:
    {{- toYaml .Values.autoscaling.metrics | nindent 4 }}
{{- end }}
---
{{- if and .Values.autoscaling.enabled .Values.autoscaling.keda.enabled }}
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: {{ include "warp.fullname" . }}
  labels:
    {{- include "warp.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    name: {{ include "warp.fullname" . }}
  pollingInterval: {{ .Values.autoscaling.keda.pollingInterval }}
  cooldownPeriod: {{ .Values.autoscaling.keda.cooldownPeriod }}
  minReplicaCount: {{ .Values.autoscaling.minReplicas }}
  maxReplicaCount: {{ .Values.autoscaling.maxReplicas }}
  triggers:
    - type: prometheus
      metadata:
        serverAddress: {{ .Values.autoscaling.keda.prometheus.serverAddress }}
        query: {{ .Values.autoscaling.keda.prometheus.query | quote }}
        threshold: {{ .Values.autoscaling.keda.prometheus.threshold | quote }}
{{- end }}
```

#### RBAC

```yaml
# templates/rbac.yaml
{{- if .Values.rbac.create }}
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: {{ include "warp.fullname" . }}
  labels:
    {{- include "warp.labels" . | nindent 4 }}
rules:
  {{- toYaml .Values.rbac.rules | nindent 2 }}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: {{ include "warp.fullname" . }}
  labels:
    {{- include "warp.labels" . | nindent 4 }}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: {{ include "warp.fullname" . }}
subjects:
  - kind: ServiceAccount
    name: {{ include "warp.serviceAccountName" . }}
    namespace: {{ .Release.Namespace }}
{{- end }}
```

---

## 7. DEPLOIEMENTS MC A GRANDE ECHELLE

### 7.1 Etat de l'art : qui fait quoi ?

| Reseau | Infra | Proxy | Orchestration | K8s ? |
|--------|-------|-------|---------------|-------|
| **Hypixel** | Bare-metal, SaltStack, MasterControl custom | BungeeCord custom | Scripts Python/Shell custom | Non (pre-K8s, migration massive en cours) |
| **Shulker (OSS)** | K8s + Agones | Velocity/BungeeCord | Operateur K8s en Rust | **Oui** |
| **Minekloud (OSS)** | K8s GKE | Gateway custom (Go) | API custom | **Oui** |
| **saulmaldonado/agones-minecraft** | GKE + Agones | BungeeCord | Agones natif + DNS controller | **Oui** |
| **PrimeCloud (PrimeMC)** | K8s + Warp | **Warp** | Custom (a definir) | **Oui** |
| **Petits reseaux** | VPS, Docker Compose, Pterodactyl | Velocity | Manuel ou scripts | Non |

### 7.2 Shulker : analyse de l'architecture

Shulker est le projet open-source le plus avance pour deployer un reseau MC sur K8s. Il est une reference essentielle pour Warp.

**CRDs Shulker** :
- `MinecraftCluster` : Ressource racine, parametres globaux du reseau (Redis, DNS)
- `ProxyFleet` : Gestion des proxies (Velocity/BungeeCord) en tant que Fleet Agones
- `MinecraftServerFleet` : Gestion des serveurs MC ephemeres (via Agones Fleet)

**Architecture** :
```
[MinecraftCluster]
    ├── [ProxyFleet] --> Agones Fleet (proxies)
    │       └── Plugin agent sur chaque proxy
    │           └── Watch K8s events -> mise a jour server list
    │
    ├── [MinecraftServerFleet: Lobby] --> Agones Fleet
    ├── [MinecraftServerFleet: SkyWars] --> Agones Fleet
    └── [Redis] --> Synchronisation inter-proxy (joueurs, messages)
```

**Ce que Warp peut apprendre de Shulker** :
1. L'agent plugin sur le proxy qui watch les events K8s -- Warp devrait avoir cette capacite nativement dans le core (pas besoin de plugin)
2. Redis pour la synchronisation inter-proxy -- Warp doit supporter ca aussi, mais de maniere optionnelle (single-proxy ne devrait pas necessiter Redis)
3. L'utilisation d'Agones Fleet pour les proxies ET les backends -- smart, mais les proxies n'ont pas vraiment besoin du lifecycle Agones (pas d'allocation au sens game server)

**Ce que Warp fera differemment** :
1. Service discovery integre dans le core (pas un plugin externe)
2. Drain system natif (pas besoin de coordination externe)
3. Support multi-mode : K8s natif (Endpoints), Agones, ou statique
4. Admin API pour l'operabilite (Shulker n'en a pas sur les proxies)
5. Metriques Prometheus exhaustives pour l'autoscaling
6. Licence Apache 2.0 (vs AGPL 3.0 pour Shulker)

### 7.3 Lecons des grands reseaux

**Hypixel (100k+ joueurs simultanes)** :
- Infrastructure pre-Kubernetes, migration en cours
- Utilise "MasterControl" custom pour le provisioning de serveurs
- Le fait qu'ils n'aient PAS K8s est un legacy, pas un choix architectural
- Leur job posts recents mentionnent K8s, ce qui confirme la migration

**Pattern commun des grands reseaux** :
1. Le proxy est le composant le plus critique -- il ne doit JAMAIS tomber
2. Les backends (serveurs de jeu) sont ephemeres -- start/stop rapide
3. Le matchmaking/routing est une logique metier complexe (pas juste du round-robin)
4. L'observabilite est souvent le parent pauvre -- Warp doit etre meilleur ici

---

## 8. DOCKER : BONNES PRATIQUES JAVA 21

### 8.1 Rappel du Dockerfile (doc existante)

Le Dockerfile production-ready est documente dans `packaging-distribution-strategy.md`. Voici les points complementaires pour le contexte K8s.

### 8.2 Detection memoire cgroups v2

Java 21 detecte automatiquement les limites cgroups v2 (`-XX:+UseContainerSupport` est actif par defaut). Points critiques pour K8s :

```
Container memory limit: 4Gi (defini dans resources.limits.memory)
    └── JVM heap max: 3Gi (75% via MaxRAMPercentage)
    └── Non-heap: 1Gi (threads, metaspace, NIO direct buffers, GC)
```

**Regles** :
- **JAMAIS** combiner `-Xmx` et `-XX:MaxRAMPercentage` -- l'un ecrase l'autre
- Pour un proxy avec beaucoup de NIO buffers (Netty), reduire a `MaxRAMPercentage=65.0` si des OOMKill surviennent
- L'OOMKiller de K8s (cgroups) kill le Pod entier si la consommation totale (heap + non-heap + natif) depasse `limits.memory`
- `-XX:+ExitOnOutOfMemoryError` cause un exit propre au lieu d'un zombie

### 8.3 Image de base : verdict

| Image | Taille | Verdict |
|-------|--------|---------|
| `eclipse-temurin:21-jre-jammy` | ~175 MB | **RECOMMANDE** -- standard industrie, glibc, compatible JNI |
| `distroless/java21` | ~192 MB | Securise mais pas de shell pour debug, pas de HEALTHCHECK shell |
| `temurin:21-jre-alpine` | ~114 MB | musl libc incompatible avec JNI (libdeflate) -- REJETE |
| Custom jlink | ~75 MB | Optimal mais maintenance lourde -- a envisager en v2 |

### 8.4 Multi-arch (amd64 + arm64)

```dockerfile
# Le JAR Java est identique pour les deux architectures
# Seul le JRE de base change (amd64 vs arm64 de Temurin)
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jre-jammy
```

```bash
# Build multi-arch CI (GitHub Actions)
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --tag ghcr.io/warp-mc/warp:$VERSION \
  --push .
```

### 8.5 Labels OCI et metadata

```dockerfile
LABEL org.opencontainers.image.title="Warp Proxy"
LABEL org.opencontainers.image.description="Cloud-native Minecraft proxy"
LABEL org.opencontainers.image.version="${VERSION}"
LABEL org.opencontainers.image.source="https://github.com/warp-mc/warp"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.vendor="Warp"
```

---

## 9. SERVICE MESH

### 9.1 Istio / Linkerd : pertinent pour MC ?

**Reponse courte : NON pour le trafic MC, potentiellement OUI pour le trafic de gestion.**

Le trafic Minecraft est un flux TCP binaire custom (pas HTTP). Les service meshes sont optimises pour HTTP/gRPC et ajoutent un overhead significatif sur le TCP brut.

### 9.2 Overhead mesure (recherche academique, 2024)

| Service Mesh | Latence P99 (mTLS) | CPU additionnel | RAM additionnel |
|-------------|-------------------|-----------------|-----------------|
| **Istio (sidecar)** | +166% | +0.81 cores/pod | +255 MiB/pod |
| **Linkerd (sidecar)** | +33% | +0.29 cores/pod | +62 MiB/pod |
| **Istio Ambient (sidecarless)** | +8% | +0.23 cores/pod | +26 MiB/pod |
| **Cilium (eBPF)** | +99% | +0.12 cores/pod | +95 MiB/pod |

Source : "Performance Comparison of Service Mesh Frameworks: the MTLS Test Case" (arXiv, 2024)

**Conclusion** : Le parsing HTTP dans Istio est le principal bottleneck (pas le mTLS lui-meme). Comme MC n'utilise pas HTTP, le parsing est inutile mais le sidecar le tente quand meme, causant un overhead enorme.

### 9.3 mTLS entre proxy et backends

**Le besoin est reel** : dans un cluster multi-tenant ou expose, chiffrer le trafic proxy-backend est souhaitable.

**Solutions par ordre de preference** :

1. **NetworkPolicy Kubernetes** (zero overhead) : Restreindre qui peut parler a qui
   ```yaml
   apiVersion: networking.k8s.io/v1
   kind: NetworkPolicy
   metadata:
     name: mc-backend-policy
   spec:
     podSelector:
       matchLabels:
         role: mc-server
     ingress:
       - from:
           - podSelector:
               matchLabels:
                 app: warp-proxy
         ports:
           - port: 25565
             protocol: TCP
   ```

2. **Cilium Network Encryption (WireGuard/IPSec)** : Chiffrement transparent au niveau du kernel, pas de sidecar
   ```yaml
   # Cilium values.yaml
   encryption:
     enabled: true
     type: wireguard
   ```

3. **Warp natif** : Implementer un handshake TLS optionnel entre Warp et les backends MC (pas standard, necessite un plugin cote serveur)

4. **Linkerd** (dernier recours) : Si mTLS est un hard requirement compliance, Linkerd a le plus faible overhead (+33% latence, +62 MiB RAM par pod)

### 9.4 Recommandation finale

```
Trafic MC (joueur -> proxy -> backend) :
  → PAS de service mesh
  → NetworkPolicy pour l'isolation
  → Cilium WireGuard si chiffrement requis

Trafic de gestion (API admin, metriques, Redis, API K8s) :
  → Optionnellement Linkerd ou Cilium mTLS
  → Souvent overkill, les NetworkPolicies suffisent
```

---

## 10. ARCHITECTURE CIBLE POUR WARP

### 10.1 Vue d'ensemble

```
┌─────────────────────────────────────────────────────────┐
│                   KUBERNETES CLUSTER                     │
│                                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │          NAMESPACE: minecraft                    │    │
│  │                                                  │    │
│  │  ┌──────────────┐    ┌──────────────────────┐   │    │
│  │  │  LoadBalancer │    │   Prometheus          │   │    │
│  │  │  :25565       │───>│   ServiceMonitor     │   │    │
│  │  │  externalTP:  │    │   + AlertManager     │   │    │
│  │  │  Local        │    └──────────────────────┘   │    │
│  │  └──────┬───────┘                                │    │
│  │         │                                        │    │
│  │  ┌──────▼───────────────────────────────────┐   │    │
│  │  │      WARP PROXY (Deployment, 2-10 pods)  │   │    │
│  │  │  ┌─────────────────────────────────────┐ │   │    │
│  │  │  │ • Service Discovery (K8s/Agones)    │ │   │    │
│  │  │  │ • Drain System (preStop hook)       │ │   │    │
│  │  │  │ • Admin API (:9901)                 │ │   │    │
│  │  │  │ • Metrics Prometheus (:9100)        │ │   │    │
│  │  │  │ • PROXY Protocol support            │ │   │    │
│  │  │  └─────────────────────────────────────┘ │   │    │
│  │  └────┬────────┬────────┬───────────────────┘   │    │
│  │       │        │        │                        │    │
│  │  ┌────▼────┐ ┌─▼─────┐ ┌▼──────────┐           │    │
│  │  │ Agones  │ │Agones │ │ Agones    │           │    │
│  │  │ Fleet:  │ │Fleet: │ │ Fleet:    │           │    │
│  │  │ Lobby   │ │SkyWars│ │ BedWars   │           │    │
│  │  │ (3pods) │ │(5pods)│ │ (10pods)  │           │    │
│  │  └─────────┘ └───────┘ └───────────┘           │    │
│  │                                                  │    │
│  │  ┌──────────────────────────────────────────┐   │    │
│  │  │  Support Services                        │   │    │
│  │  │  • Redis (sync inter-proxy)              │   │    │
│  │  │  • KEDA (autoscaling event-driven)       │   │    │
│  │  │  • PDB (protection disruption)           │   │    │
│  │  └──────────────────────────────────────────┘   │    │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ┌────────────────────────────────────────────────┐     │
│  │ INFRA                                          │     │
│  │ • Cilium CNI (eBPF, DSR, Maglev)              │     │
│  │ • Agones Controller                            │     │
│  │ • Prometheus Operator + Grafana                │     │
│  │ • Argo Rollouts (optionnel, canary)            │     │
│  └────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────┘
```

### 10.2 Ce que Warp doit implementer nativement

| Feature | Priorite | Description |
|---------|----------|-------------|
| **PROXY Protocol v2 (decodage)** | P0 | Recevoir l'IP reelle du joueur derriere un LB K8s |
| **Admin API /drain/start** | P0 | Hook preStop pour zero-downtime |
| **Readiness endpoint /ready** | P0 | Retourner 503 pendant le drain |
| **Liveness endpoint /health/live** | P0 | Detecter les crashs |
| **Metriques Prometheus** | P0 | Deja concu (doc observabilite) |
| **K8s Endpoints Watch** | P1 | Service discovery des backends depuis K8s |
| **Agones GameServer Watch** | P1 | Service discovery via CRDs Agones |
| **Agones Allocation API** | P1 | Allouer un GameServer pour un joueur |
| **Env var POD_NAME/POD_IP** | P1 | Identification du pod dans le cluster |
| **Graceful shutdown (SIGTERM)** | P0 | Reagir proprement au signal |
| **Scale-from-zero hook** | P2 | Reveiller un backend a la demande |
| **Transfer API** | P1 | Transferer un joueur vers un autre proxy (drain) |

### 10.3 Checklist du Helm chart

- [ ] Deployment avec rolling update (maxSurge=1, maxUnavailable=0)
- [ ] Service LoadBalancer avec externalTrafficPolicy: Local
- [ ] ServiceAccount + RBAC pour K8s API access
- [ ] ConfigMap pour warp.yaml
- [ ] Secret optionnel (forwarding secret, API keys)
- [ ] PodDisruptionBudget (minAvailable: 80%)
- [ ] HPA ou KEDA ScaledObject (au choix via values)
- [ ] ServiceMonitor (Prometheus Operator)
- [ ] PrometheusRule (alertes)
- [ ] Pod anti-affinity (spread across nodes)
- [ ] terminationGracePeriodSeconds: 3600
- [ ] Probes: readiness, liveness, startup
- [ ] preStop hook: /drain/start
- [ ] NetworkPolicy optionnelle
- [ ] values.schema.json pour validation
- [ ] Tests CI (helm test, helm lint, kubeval)

---

## SOURCES

- [Agones - Dedicated Game Server Hosting on Kubernetes](https://agones.dev/site/)
- [Agones GameServer Specification](https://agones.dev/site/docs/reference/gameserver/)
- [Agones Fleet Specification](https://agones.dev/site/docs/reference/fleet/)
- [Agones FleetAutoscaler Specification](https://agones.dev/site/docs/reference/fleetautoscaler/)
- [Agones GameServerAllocation Specification](https://agones.dev/site/docs/reference/gameserverallocation/)
- [Shulker - Kubernetes Operator for Minecraft](https://github.com/jeremylvln/Shulker)
- [Shulker Architecture](https://shulker.jeremylvln.fr/latest/guide/architecture.html)
- [agones-minecraft - GKE Minecraft deployment](https://github.com/saulmaldonado/agones-minecraft)
- [KEDA - Kubernetes Event-driven Autoscaling](https://keda.sh/)
- [KEDA Prometheus Scaler](https://keda.sh/docs/2.19/scalers/prometheus/)
- [KEDA ScaledObject Spec](https://keda.sh/docs/2.19/reference/scaledobject-spec/)
- [Kubernetes HPA](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/)
- [Kubernetes PodDisruptionBudget](https://kubernetes.io/docs/tasks/run-application/configure-pdb/)
- [Kubernetes Service (externalTrafficPolicy)](https://kubernetes.io/docs/concepts/services-networking/service/)
- [Kubernetes Graceful Shutdown Best Practices](https://cloud.google.com/blog/products/containers-kubernetes/kubernetes-best-practices-terminating-with-grace)
- [Minekloud - Minecraft Protocol Reverse Proxy](https://dev.to/kiliandeca/we-built-a-minecraft-protocol-reverse-proxy-2e4f)
- [Cilium DSR - Direct Server Return](https://blog.stonegarden.dev/articles/2026/02/cilium-dsr/)
- [Cilium eBPF kube-proxy Replacement](https://docs.cilium.io/en/stable/operations/performance/tuning/)
- [Service Mesh Performance Comparison (arXiv 2024)](https://arxiv.org/html/2411.02267v1)
- [Argo Rollouts - Progressive Delivery](https://argoproj.github.io/rollouts/)
- [Hypixel Infrastructure Discussions](https://hypixel.net/threads/why-not-use-kubernetes.4864720/)
- [itzg Minecraft Server Helm Charts](https://github.com/itzg/minecraft-server-charts)
- [Java Docker Best Practices 2025](https://dev.to/devaaai/best-docker-base-images-and-performance-optimization-for-java-applications-in-2025-kdd)
- [Helm Chart Reliability 2025](https://www.prequel.dev/blog-post/the-real-state-of-helm-chart-reliability-2025-hidden-risks-in-100-open-source-charts)
