# Recherche communautaire : Points de douleur des proxys Minecraft (Velocity / BungeeCord)

**Date de recherche** : 31 mars 2026
**Sources** : Reddit (r/admincraft), SpigotMC, GitHub Issues, PaperMC Docs, blogs techniques, projets alternatifs (Gate, Infrarust, TransferProxy, Gourd, Velocity-CTD)

---

## 1. Plaintes de performance

### 1.1 Compression / Decompression CPU : le goulot d'etranglement principal

Sur un proxy Velocity sans plugins, **la majorite du temps CPU est consommee par la decompression et recompression des paquets**. Si le serveur backend a la compression activee (par defaut, les paquets > 256 octets sont compresses), Velocity est force de :

1. Decompresser les paquets venant du backend
2. Les analyser (souvent sans rien trouver d'interessant)
3. Les recompresser pour les envoyer au client

Ce cycle decompression-recompression est un **gaspillage massif de CPU** pour les paquets que le proxy n'a pas besoin d'inspecter. Velocity attenueC le probleme en utilisant `libdeflate` (2x plus rapide que zlib) sur Linux, mais le probleme fondamental demeure : **il n'y a pas de mode passthrough pour les paquets non-inspectes**.

> **Opportunite Warp** : Le blind forwarding (relayage aveugle) eliminerait ce goulot pour les paquets non-inspectes par les plugins.

### 1.2 Entity ID Rewriting (heritage BungeeCord)

BungeeCord reecrit les entity IDs dans chaque paquet -- une operation **couteuse, buguee et incompatible avec les mods**. Velocity a resolu ce probleme en forcant le client a changer son entity ID via un paquet login+respawn plutot que de reecrire les paquets.

Cependant, cette approche a ses propres problemes :
- **Bugs sur clients moddes** : deconnexions aleatoires, crashes
- **Baisse de performance de ~20%** sur certains setups sans entity rewrite (tests de Leymooo, issue Velocity #7)

### 1.3 Latence de connexion initiale

Les joueurs rapportent un **lag cote client de 5 a 10 secondes** lors de la connexion initiale via le proxy. Ce probleme est amplifie par ViaVersion (ecran de chargement gele pendant ~30 secondes avec certaines combinaisons de versions client/serveur).

### 1.4 Benchmarks compares

| Proxy | Joueurs concurrents | Memoire | Observation |
|---|---|---|---|
| **BungeeCord** | ~500 avant degradation | Haute (JVM) | Latence et memoire elevees sous charge |
| **Velocity** | 1000+ avec degradation minimale | Moyenne (JVM) | Jusqu'a 8x plus rapide que BungeeCord en throughput |
| **Gate** | Milliers | ~10 MB | Pas de JVM, binaire Go |
| **Infrarust** | Milliers | Tres faible | Zero-copy forwarding, Rust |

---

## 2. Fonctionnalites manquantes les plus demandees

### 2.1 Systeme de drain / graceful shutdown

**C'est le pain point le plus recurrent dans les discussions communautaires.**

Les administrateurs de grands reseaux se plaignent qu'il n'existe **aucun mecanisme natif de drain** dans Velocity ou BungeeCord :
- Pas de moyen d'empecher les nouvelles connexions tout en laissant les joueurs actuels finir leur session
- Le redemarrage du proxy **deconnecte brutalement tous les joueurs**
- La seule solution est un patchwork de plugins tiers (Proxy-Utils, AutoProxyShutdown)
- Les operateurs doivent mettre manuellement le proxy en maintenance, deplacer les joueurs, puis arreter

> **Thread SpigotMC notable** : "Best way to do a proxy restart in a big network" -- les reponses montrent qu'il n'existe pas de solution satisfaisante integree.

### 2.2 Hot reload de la configuration

- Velocity supporte `/velocity reload` mais **toutes les modifications ne sont pas appliquees** (forwarding mode, auth settings necessitent un restart complet)
- BungeeCord n'a pas de reload fiable
- Gate se demarque avec un **watch automatique du fichier de config** qui applique les changements instantanement sans deconnecter les joueurs
- Infrarust permet de **deposer un fichier .toml dans servers/ et le proxy le detecte automatiquement** sans redemarrage

### 2.3 Communication directe proxy-backend (sans joueur connecte)

**Issue Velocity #1488** (13 commentaires, demande populaire) : il n'existe aucun moyen de communiquer entre le proxy et un serveur backend sans joueur connecte. Les plugin messages necessitent une connexion active. Cas d'usage :
- Synchronisation de configuration au demarrage
- Envoi de donnees joueur lors de la deconnexion
- Etat partage entre serveurs

### 2.4 Support natif multi-proxy (scaling horizontal)

Velocity et BungeeCord **n'ont aucun support natif pour le scaling horizontal**. Pour connecter plusieurs instances de proxy :
- Il faut RedisBungee/ValioBungee (plugin tiers)
- Problemes de synchronisation de temps, corruption de cache, null pointer exceptions
- Degradation gracieuse partielle seulement
- Pas d'integration avec les load balancers modernes (HAProxy, etc.)

> **Issue Velocity #1504** : La demande d'ajout du PROXY Protocol vers les backends a ete **refusee** (closed as NOT_PLANNED), frustrante pour les architectures multi-couches.

### 2.5 Queue system integre

Les grands reseaux (type Hypixel) ont besoin d'un systeme de file d'attente pour gerer des milliers de joueurs. Velocity n'en a pas. Le fork **Velocity-CTD** a du l'ajouter en tant que fonctionnalite integree car la demande etait si forte.

### 2.6 Mode de forwarding personnalise

**Issue Velocity #888** : L'enum `PlayerInfoForwarding` ne permet que NONE, MODERN, BUNGEEGUARD. L'option NONE modifie l'UUID du joueur vers une version offline (pas de skins, messages signes casses). Il n'y a **aucune option pour laisser les UUID inchanges** avec un mode custom.

---

## 3. Frustrations des developpeurs de plugins

### 3.1 Ecosysteme de plugins fragmente

C'est l'un des **plus gros obstacles a l'adoption de Velocity** :
- Les plugins BungeeCord **ne fonctionnent PAS** sur Velocity et vice versa
- L'outil experimental "Snap" pour faire tourner les plugins BungeeCord sur Velocity est instable
- BungeeCord a plus de plugins tiers grace a son anciennete
- Les developpeurs doivent maintenir **deux versions** de chaque plugin (ou choisir un camp)
- Velocity refuse intentionnellement la compatibilite BungeeCord pour encourager son propre ecosysteme

### 3.2 API Velocity : limitations concretes

- **Impossible d'editer les messages vanilla** (join/leave, chat) depuis le proxy
- **Impossible d'enregistrer un event listener dans le constructeur** du plugin -- il faut attendre ProxyInitializeEvent
- **CommandAPI limitee** : tres peu d'arguments supportes sur Velocity car implementes cote backend
- **Conditions de course dans les evenements de connexion** (Issue #1013) :
  - La connexion est desenregistree AVANT que DisconnectEvent soit fire
  - Un joueur peut se reconnecter alors que l'ancien nettoyage n'est pas termine
  - Pas de garanties officielles de concurrence
  - Les developpeurs doivent implementer des caches complexes (weak keys, maps multiples)

### 3.3 Systeme d'evenements asynchrone

Velocity 3.0.0 execute **tous les event listeners de facon asynchrone par defaut** pour compatibilite. Polymer (la prochaine version majeure) changera ce comportement, cassant potentiellement les plugins existants. Les developpeurs devront explicitement utiliser des continuations ou event tasks.

### 3.4 Dependances et mises a jour lentes

Le fork Velocity-CTD a ete cree specifiquement a cause de :
- **Plugins mal faits bloques par les limitations de l'API**
- **Manque de maintenance/support de certaines dependances**
- **Mises a jour peu frequentes des dependances integrales**

---

## 4. Problemes de deploiement Cloud / Kubernetes

### 4.1 Velocity et BungeeCord ne sont PAS cloud-native

Les proxys Java traditionnels posent de multiples problemes dans un contexte cloud :

- **JVM lourde** : empreinte memoire elevee, temps de demarrage long
- **Pas de service discovery** : les backends doivent etre configures statiquement
- **Pas de health checks standardises** : pas de readiness/liveness probes integrees
- **Pas de reconnexion automatique** : quand un pod backend est supprime/recree, le proxy ne reagit pas
- **Pas de metrics native** : pas de support Prometheus/OpenTelemetry integre (necessite des plugins tiers comme UnifiedMetrics)
- **Pas de support Helm/Kustomize officiel** : il existe un chart `minecraft-proxy` tiers mais non officiel

### 4.2 Limitations des reverse proxies standards

Les outils cloud classiques (Traefik, NGINX) **ne peuvent pas router le trafic Minecraft** de facon intelligente :
- Ils operent en Layer 7 (HTTP) ou Layer 4 (TCP brut)
- Au niveau TCP, **impossible de distinguer quel serveur le joueur veut rejoindre**
- Le protocole Minecraft n'est pas HTTP, donc pas de routage basee sur les headers

### 4.3 Gate : la reponse cloud-native

Gate a ete cree specifiquement pour repondre a ces problemes :
- Binaire unique de ~10 MB (pas de JVM)
- Images Docker officielles, manifestes Kubernetes inclus
- Service discovery via DNS intra-cluster
- Reconnexion automatique quand un pod est supprime
- Support OpenTelemetry natif (Prometheus, Grafana, Jaeger)
- Hot reload de la configuration

### 4.4 Architecture multi-region

Le concept de **ProxyNet** (Gate/Minekube) adresse le besoin de reseaux distribues geographiquement, mais c'est un probleme que ni Velocity ni BungeeCord ne tentent de resoudre.

---

## 5. Problemes de scalabilite

### 5.1 Proxy unique = point de defaillance unique

Un proxy Velocity/BungeeCord est un **single point of failure**. Si le proxy tombe, tout le reseau est inaccessible. Le scaling horizontal necessite :
- RedisBungee ou equivalent
- Un load balancer TCP devant les proxys
- Une synchronisation d'etat complexe (joueurs connectes, serveurs disponibles)

### 5.2 Synchronisation cross-proxy defaillante

RedisBungee (la solution standard) souffre de :
- Problemes de synchronisation temporelle entre instances
- Corruption de cache (null pointer exceptions)
- Erreurs "Unable to get connection from pool" lors de pics de charge
- Pas de degradation gracieuse fiable

### 5.3 Pas d'autoscaling

Aucun proxy Minecraft ne supporte nativement l'autoscaling :
- Pas de mecanisme pour ajouter/retirer des instances de proxy dynamiquement
- Pas d'integration avec Kubernetes HPA
- La solution Gate/Minekube propose un concept avec ProxyNet + Deployments/StatefulSets avec HPA, mais c'est encore experimental

---

## 6. Preoccupations de securite

### 6.1 Modele de forwarding BungeeCord : fondamentalement casse

Le modele de securite de BungeeCord (`ip_forward`) est **dangereux par design** :
- Fait confiance aux connexions basee sur l'IP du proxy
- Si un attaquant atteint un backend directement (firewall mal configure, IP leakee), il peut **usurper l'identite de n'importe quel joueur, y compris les admins**
- Les donnees joueur sont transmises en **texte clair** dans le handshake
- Le patch BungeeGuard est un pansement sur un modele fondamentalement casse

### 6.2 Velocity modern forwarding : mieux mais pas parfait

Velocity signe les donnees joueur avec un HMAC utilisant un secret partage. C'est significativement plus sur, mais :
- Necessite des mods tiers pour les backends Forge/NeoForge (Proxy Compatible Forge, NeoVelocity)
- Le secret doit etre distribue manuellement a chaque backend
- Pas de rotation automatique des secrets

### 6.3 Protection anti-DDoS / anti-bot

Velocity **n'a aucune protection anti-bot integree**. Les solutions sont toutes des plugins tiers :
- VeloCity-Security (DDoS Layer 4 & 7, anti-VPN)
- VeloFlame (fork de Velocity-CTD avec anti-bot au niveau kernel)
- ProxyFlow (firewall anti-VPN, anti-bot)

Gate inclut un framework de securite integre (validation de paquets, gestion des connexions, failover). Infrarust inclut une protection DDoS et un systeme de ban integres.

### 6.4 Chiffrement end-to-end casse

BungeeCord et Velocity **terminent le chiffrement** de la connexion Minecraft. Les serveurs derriere le proxy sont forces de tourner en offline-mode, ce qui desactive l'authentification Mojang cote backend.

Un article technique (DEV.to - Kilian Deca) souligne que c'est **inacceptable pour un hebergeur professionnel**. Leur solution : ne lire que les premiers paquets non-chiffres pour le routage, puis laisser passer le chiffrement de bout en bout.

---

## 7. Problemes d'experience developpeur

### 7.1 Documentation et communaute

- La documentation de Velocity est correcte mais **manque de profondeur** sur les cas avances
- BungeeCord : "activement hostile envers le support du modding Minecraft" (citation de la communaute)
- La gouvernance de BungeeCord est autoritaire : "le mot de md_5 fait loi"
- Un contributeur de Velocity a ete "reprimande au moins deux fois par md_5" pour avoir soumis un correctif de securite a BungeeCord

### 7.2 Compatibilite multi-version

Le support multi-version necessite ViaVersion, qui :
- Doit etre installe soit sur le proxy soit sur le backend, **mais pas les deux**
- Cause des ecrans de chargement geles (~30 secondes) avec certaines combinaisons de versions
- Cree des erreurs "Incompatible client!" quand le proxy ne connait pas la derniere version
- Ajoute une couche de complexite et de bugs potentiels

### 7.3 Compatibilite avec les serveurs moddes

Velocity ne supporte pas nativement les backends Forge/NeoForge pour le modern forwarding :
- Il faut installer **Proxy Compatible Forge** ou **NeoVelocity** sur chaque backend modde
- Des incompatibilites de signature entre systemes (issue Sinytra Connector #1736)
- Le Fabric Networking API peut creer des conflits avec les login packets custom

### 7.4 Integration Bedrock/Java cross-play

L'integration Geyser (Bedrock-to-Java) sur Velocity pose des problemes :
- Consommation memoire elevee (>3 GB rapportes)
- Crashes du proxy lors de la connexion de joueurs Bedrock
- Conflits de configuration avec Floodgate
- Protocole UDP (Bedrock) vs TCP (Java) cause des problemes chez certains hebergeurs

---

## 8. Proxys alternatifs et pourquoi ils existent

### 8.1 Gate (Go) - Cloud-native, leger

**Motivation** : Velocity et BungeeCord sont trop lourds, pas cloud-native, et lies a la JVM.

| Fonctionnalite | Gate | Velocity |
|---|---|---|
| Langage | Go | Java |
| Memoire | ~10 MB | ~200+ MB |
| JVM requise | Non | Oui |
| Kubernetes natif | Oui | Non |
| Hot reload config | Oui (file watch) | Partiel (/velocity reload) |
| Cross-play Bedrock | Integre (Geyser) | Plugin tiers |
| OpenTelemetry | Natif | Plugin tiers |
| SDKs | Go, TS, Python, Rust, Kotlin, Java | Java seulement |
| Mode Lite (reverse proxy) | Oui | Non |

### 8.2 Infrarust (Rust) - Performance maximale, zero-copy

**Motivation** : Performance maximale, simplicite operationnelle, observabilite native.

Fonctionnalites cles :
- **5 modes de proxy** : passthrough, zerocopy, client_only, offline, server_only
- Zero-copy packet forwarding (pas de copie memoire)
- Connection pooling
- Status caching
- Admin panel web integre avec REST API
- OpenTelemetry natif
- Hot reload (deposer un .toml, le proxy le detecte)
- Protection DDoS et ban system integres
- Docker : "no config files needed for Docker-managed servers"

### 8.3 TransferProxy (Java) - Transfer Packet natif

**Motivation** : Utiliser le Transfer Packet natif de Minecraft 1.20.5+ au lieu du mecanisme de proxy traditionnel.

- Leger et peu couteux en ressources
- Supporte des milliers de requetes simultanees
- Systeme de cookies pour persister les donnees entre transferts
- Architecture modulaire avec systeme de plugins

### 8.4 Gourd (Rust, base Pumpkin) - Resilience

**Motivation** : Resilience et gestion des defaillances.

- Transferts inities par le backend via canal custom
- Health checks TCP periodiques
- Reconnexion automatique en cas de defaillance
- Rechargement de configuration dynamique
- Rate limiting et controle d'acces integres

### 8.5 Velocity-CTD (Fork Velocity) - Fonctionnalites manquantes

**Motivation** : Combler les lacunes de Velocity sans attendre les releases officielles.

Ajouts :
- Integration Redis native (remplace RedisBungee)
- Systeme de queue integre (milliers de joueurs)
- Multi-forwarding (methodes differentes par backend)
- Commandes reseau integrees (/alert, /find, /transfer, /sudo)
- Dependances mises a jour plus frequemment

---

## 9. Synthese des opportunites pour Warp

| Theme | Douleur communautaire | Opportunite Warp |
|---|---|---|
| **Performance** | Decompression/recompression inutile de chaque paquet | Blind forwarding (relayage aveugle) |
| **Drain system** | Aucun mecanisme natif de drain/graceful shutdown | Drain system integre au coeur |
| **Cloud-native** | Velocity/BungeeCord non adaptes a K8s | Design cloud-native des le depart |
| **Scaling horizontal** | RedisBungee fragile, pas de support natif | Multi-proxy natif |
| **Plugin API** | Race conditions, limitations d'events, ecosysteme fragmente | API propre, sans heritage BungeeCord |
| **Securite** | Pas d'anti-bot natif, forwarding legacy dangereux | Protection integree |
| **Hot reload** | Reload partiel, necessite souvent un restart | Hot reload complet |
| **Observabilite** | Pas de metrics natives | OpenTelemetry/Prometheus natif |
| **Multi-version** | ViaVersion fragile et complexe | Support multi-version integre |
| **Communication proxy-backend** | Limitee aux plugin messages (necessite joueur connecte) | Canal de communication directe |
| **Transfer Packet** | Nouveau paradigme (1.20.5+), pas integre dans Velocity | Support natif du Transfer Packet |
| **Configuration** | Statique, pas de service discovery | Service discovery dynamique |

---

## Sources

- [PaperMC - Why Velocity](https://docs.papermc.io/velocity/why-velocity/)
- [PaperMC - Comparing with other proxies](https://docs.papermc.io/velocity/comparisons-to-other-proxies/)
- [SpigotMC - Velocity vs BungeeCord for 1.8 Network in 2026](https://www.spigotmc.org/threads/velocity-vs-bungeecord-for-1-8-network-in-2026.715478/)
- [SpigotMC - Bungeecord or Waterfall or Velocity](https://www.spigotmc.org/threads/bungeecord-or-waterfall-or-velocity.586775/)
- [SpigotMC - Best way to do a proxy restart in a big network](https://www.spigotmc.org/threads/best-way-to-do-a-proxy-restart-in-a-big-network.564468/)
- [SpigotMC - Designing a Scalable Multi-Proxy System](https://www.spigotmc.org/threads/designing-a-scalable-multi-proxy-system.651133/)
- [GitHub - Velocity Issues](https://github.com/PaperMC/Velocity/issues)
- [GitHub - Velocity #1488 - Direct communication with Paper servers](https://github.com/PaperMC/Velocity/issues/1488)
- [GitHub - Velocity #1504 - HAProxy PROXY Protocol](https://github.com/PaperMC/Velocity/issues/1504)
- [GitHub - Velocity #888 - Custom forwarding mode](https://github.com/PaperMC/Velocity/issues/888)
- [GitHub - Velocity #1013 - Concurrency guarantees of connection events](https://github.com/PaperMC/Velocity/issues/1013)
- [GitHub - Velocity #7 - About entity rewrite](https://github.com/VelocityPowered/Velocity/issues/7)
- [GitHub - Velocity Milestones (Polymer)](https://github.com/PaperMC/Velocity/milestones)
- [GitHub - AKIRA-MC/proxy (Velocity-CTD)](https://github.com/AKIRA-MC/proxy)
- [GitHub - Gate (Minekube)](https://github.com/minekube/gate)
- [Gate - Next Generation Minecraft Proxy](https://gate.minekube.com/)
- [Gate - Deploy on Kubernetes](https://gate.minekube.com/guide/install/kubernetes)
- [GitHub - Infrarust](https://github.com/shadowner/infrarust)
- [Infrarust - Proxy Overview](https://infrarust.dev/proxy/)
- [GitHub - TransferProxy](https://github.com/YvanMazy/TransferProxy)
- [GitHub - Gourd](https://github.com/Purdze/Gourd)
- [GitHub - Switch-to-Velocity](https://github.com/Syrent/Switch-to-Velocity)
- [GitHub - Snap (BungeeCord plugins on Velocity)](https://github.com/Phoenix616/Snap)
- [GitHub - Minecraft K8s Concepts](https://github.com/robinbraemer/draft-minecraft-k8s)
- [DEV.to - We built a Minecraft protocol reverse proxy](https://dev.to/kiliandeca/we-built-a-minecraft-protocol-reverse-proxy-2e4f)
- [SoulFire - Testing Minecraft Proxy Networks](https://soulfiremc.com/blog/testing-minecraft-proxy-networks)
- [Admincraft Discord - Which proxy to use?](https://www.answeroverflow.com/m/1442870481077145661)
- [Pufferfish Docs - Velocity vs Waterfall vs BungeeCord](https://docs.pufferfish.host/general/velocity-vs-waterfall-vs-bungeecord/)
- [EUGameHost - Minecraft Proxies Explained](https://www.eugamehost.com/blog/bungeecord-waterfall-velocity-minecraft-proxy-hosting-guide/)
- [GitHub - BungeeCord Issues](https://github.com/SpigotMC/BungeeCord/issues)
- [VeloCity-Security Plugin](https://www.spigotmc.org/resources/velocity-security.128704/)
- [Proxy-Utils Plugin](https://modrinth.com/plugin/proxy-utils)
- [UnifiedMetrics Plugin](https://modrinth.com/plugin/unifiedmetrics)
- [GeyserMC - Common Issues](https://geysermc.org/wiki/geyser/common-issues/)
