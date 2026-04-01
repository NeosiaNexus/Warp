# Recherche consolidee : Faiblesses des proxys Minecraft existants

**Date** : 31 mars 2026
**Sources** : GitHub Issues (Velocity 70+ issues, BungeeCord 50+ issues), Reddit r/admincraft, SpigotMC forums, blogs techniques, projets alternatifs (Gate, Infrarust, TransferProxy, Gourd, Velocity-CTD)

---

## VERDICT GLOBAL

Les problemes identifies se repartissent en **4 niveaux de gravite** :

| Niveau | Description | Nb problemes |
|--------|-------------|-------------|
| **CRITIQUE** | Bloque des cas d'usage entiers, pas de workaround propre | 12 |
| **MAJEUR** | Frustration quotidienne, workarounds fragiles | 15 |
| **IMPORTANT** | Amelioration significative de l'experience | 11 |
| **BONUS** | Nice-to-have, differenciateur marketing | 8 |

---

## 1. PROBLEMES CRITIQUES (12)

### 1.1 Compression/decompression gaspillee (Velocity #594)
- **Probleme** : Chaque paquet est decompresse -> deserialise -> reserialise -> recompresse, meme quand le proxy n'a rien a inspecter (~90% des paquets en PLAY state)
- **Impact** : Principale source de CPU sur un proxy Velocity. Estimation : 4-5x gain possible avec passthrough (estimation des mainteneurs eux-memes)
- **Etat** : Issue ouverte depuis 2021, zero progression en 4 ans
- **Solution Warp** : Blind forwarding. Les paquets non-enregistres par un plugin passent en raw bytes sans decompression

### 1.2 Zero drain / graceful shutdown (absent de Velocity ET BungeeCord)
- **Probleme** : `SIGTERM` = kick tous les joueurs. Pas de mode drain, pas d'API pour stopper les nouvelles connexions, pas d'integration health check
- **Impact** : Chaque mise a jour du proxy = downtime pour TOUS les joueurs. Les grands reseaux construisent des systemes custom (PrimeMC: DrainHttpServer + HAProxy agent + drain.sh)
- **Etat** : Velocity #431 (22 reactions) ouvert depuis 2021 par astei lui-meme, zero implementation
- **Solution Warp** : Drain multi-phase natif avec Transfer Packet (1.20.5+), probes K8s, HAProxy agent-check

### 1.3 Race conditions dans les evenements de connexion (Velocity #1013, #289, #1691 / BungeeCord #2815)
- **Probleme** : Quand un joueur se reconnecte rapidement, DisconnectEvent de l'ancienne session peut nettoyer les donnees de la nouvelle. La connexion est desenregistree AVANT que DisconnectEvent soit fire. DisconnectEvent fire pour des connexions n'ayant jamais eu de LoginEvent
- **Impact** : Plugins majeurs touches (LuckPerms, BungeeTabListPlus, BuycraftX). Les devs doivent implementer des caches complexes avec weak keys
- **Etat** : Maintainers reconnaissent le probleme, suggerent des workarounds au lieu de corriger
- **Solution Warp** : Garantie stricte : Disconnect(ancien) COMPLETE avant Login(nouveau) pour le meme UUID. Evenements granulaires par phase

### 1.4 Logique de switch serveur fragile (Velocity #1251, #1723)
- **Probleme** : Deconnexions aleatoires lors du changement de serveur ("Unexpectedly disconnected from remote server"). Le proxy pause la lecture du backend pendant la transition, causant des timeouts. La packet queue pour la phase CONFIGURATION est implementee a la va-vite (electronicboy: "get it out the door")
- **Impact** : 53 commentaires sur #1251, reproduit par de multiples reseaux en production
- **Etat** : electronicboy admet que "recrire la logique de server switching est dans la liste des choses qu'il aimerait faire"
- **Solution Warp** : Machine a etats rigoureuse Handshake -> Login -> Configuration -> Play. Pas de packet queue bricolee

### 1.5 BungeeCord cesse d'accepter les connexions aleatoirement (BungeeCord #3358 + 6 issues liees)
- **Probleme** : Le proxy devient "gele" : joueurs connectes restent, aucune nouvelle connexion acceptee. Pas de log, pas d'erreur. Reproduit meme sans plugins
- **Impact** : Issues #171, #1959, #2271, #2644, #2984, #3276 sur plusieurs annees
- **Solution Warp** : Watchdog thread detectant les event loops bloquees + mecanisme de recovery

### 1.6 ServerSwitchEvent fire en mauvais etat (BungeeCord #3519 [11 reactions], #3542)
- **Probleme** : Depuis 1.20.2, ServerSwitchEvent est appele AVANT que la connexion soit en phase GAME. Envoyer quoi que ce soit pendant cet evenement = kick immediat
- **Impact** : Issue la plus reactionnee de BungeeCord. Tous les plugins qui envoient des messages au switch serveur sont casses
- **Solution Warp** : Les evenements post-switch ne sont emis qu'une fois la phase GAME atteinte. File d'attente automatique des paquets API pendant les transitions

### 1.7 Systeme d'evenements asynchrones defectueux (BungeeCord #1858)
- **Probleme** : Race conditions, priorites non respectees avec registerIntent(), events "hung" (intent jamais complete) bloquent tout
- **Etat** : Debat depuis 10 ans, aucune solution adoptee
- **Solution Warp** : Evenements avec garanties d'ordre, timeouts, thread-safety par design

### 1.8 Attaque de decompression / OOM (Velocity #1742, mars 2026)
- **Probleme** : Un attaquant envoie des paquets compresses qui se decompressent a ~8MB chacun, causant un OOM kill
- **Impact** : Rapporte par un grand reseau, toujours ouvert
- **Solution Warp** : Rate-limiting + limites configurables de taille decompresse par connexion. Le blind forwarding reduit aussi la surface d'attaque (moins de paquets decompresses)

### 1.9 IP forwarding usurpable (BungeeCord #1328, ouvert depuis 2015)
- **Probleme** : L'IP/UUID du joueur est insere en texte clair dans le handshake. Aucun HMAC, aucun secret. Usurpation triviale si un attaquant atteint un backend directement
- **Impact** : Probleme de securite #1 poussant les migrations vers Velocity
- **Solution Warp** : HMAC-SHA256 (compatible Velocity modern forwarding). Plus : secrets par serveur, rotation automatique possible

### 1.10 Pas de protection anti-bot/DDoS native (Velocity + BungeeCord)
- **Probleme** : Aucune protection integree. Solutions = plugins tiers (VeloCity-Security, VeloFlame, ProxyFlow, LimboFilter)
- **Impact** : BungeeCord crash avec 200-300 requetes HTTP/seconde (#2854). Les attaques de ping flood saturent le proxy
- **Solution Warp** : Rate-limiting natif par IP et par type de requete. Detection et fermeture immediate des connexions non-MC. Cache de ping (comme Gate Lite)

### 1.11 Forwarding mode unique et global (Velocity #566, #888)
- **Probleme** : Un seul mode de forwarding + un seul secret pour TOUS les backends. Impossible d'avoir serveur A en modern forwarding et serveur B en legacy
- **Impact** : Bloque les setups heterogenes (Forge + Vanilla), les setups multi-proprietaires
- **Etat** : #566 ouvert depuis 2021, zero commentaires en 4 ans. PR #1749 (mars 2026) tente enfin d'implementer le mixed forwarding
- **Solution Warp** : Configuration par serveur : forwarding mode, secret, compression, timeouts

### 1.12 Pas de communication directe proxy-backend (Velocity #1488)
- **Probleme** : Les plugin messages necessitent un joueur connecte. Impossible de communiquer entre proxy et backend sans joueur
- **Cas d'usage** : Synchro de config au demarrage, envoi de donnees joueur a la deconnexion, etat partage entre serveurs
- **Solution Warp** : Canal headless proxy <-> backend (TCP persistent ou gRPC)

---

## 2. PROBLEMES MAJEURS (15)

### 2.1 Support Forge/mods casse
- **Velocity #248** : Support Forge 1.14+ absent (retire de la roadmap). PR communautaire #690 rejetee apres des mois de travail, causant une frustration massive (dev Pixelmon: "disgusting")
- **BungeeCord** : Entity ID rewriting incompatible avec les mods. Canaux de plugin messages Forge mal proxies
- **Solution Warp** : Le blind forwarding rend le support modde quasi-natif (les paquets inconnus passent en raw bytes)

### 2.2 Entity ID rewriting (BungeeCord heritage)
- **Probleme** : BungeeCord scanne CHAQUE paquet pour reecrire les entity IDs. Extremement couteux, fragile, et source de bugs inexpliquables (fishing hooks, guardian beams, Pixelmon battles)
- **Solution Warp** : Trick JoinGame/Respawn (comme Velocity). JAMAIS de scan d'entity IDs

### 2.3 Multiple listeners impossibles (Velocity #1046, 17 reactions)
- **Probleme** : Un seul bind address, un seul MOTD, un seul max-players. BungeeCord le supporte nativement
- **Solution Warp** : Multi-bind natif avec config independante par listener

### 2.4 Chat manipulation cassee depuis 1.19.1 (Velocity #804, #749, #756)
- **Probleme** : Impossible d'annuler ou modifier un message chat signe. Le proxy deconnecte le joueur si un plugin tente de le faire
- **Reponse maintainers** : "Very unlikely we continue to support chat manipulation on the proxy side"
- **Solution Warp** : Documenter clairement les limites du chat signing. Proposer des alternatives (system messages, actionbar). Explorer si le blind forwarding permet de laisser passer les messages signes sans les toucher

### 2.5 Pas de hot-restart / hot-reload complet (Velocity #431, 22 reactions)
- **Probleme** : Forwarding mode, bind address, secrets necessitent un restart complet + deconnexion de tous les joueurs
- **Solution Warp** : Hot-reload pour les configs non-structurelles. Drain system pour les changements structurels

### 2.6 Tab list API incomplete (Velocity #870, #605, #1455)
- **Probleme** : Pas d'evenement pour intercepter les changements de tab list du backend. Pas de tri/prefixes cote API
- **Solution Warp** : API tab list complete avec event d'interception

### 2.7 Ecosysteme de plugins fragmente
- **Probleme** : Plugins BungeeCord incompatibles avec Velocity. Devs doivent maintenir 2 versions. L'outil Snap est instable
- **Solution Warp** : API propre (pas de compatibilite BungeeCord). Fournir des outils de migration. Rendre l'API suffisamment meilleure pour justifier la migration

### 2.8 Pas de scaling horizontal natif (BungeeCord #217, ouvert depuis 2013)
- **Probleme** : Single-instance par design. RedisBungee = fragile, problemes de synchro, null pointers
- **Solution Warp** : Pas MVP, mais architecture pensee pour le multi-instance des le depart (etat externalise)

### 2.9 Scoreboards/boss bars non nettoyes au switch (BungeeCord #3503)
- **Probleme** : Kick si le nouveau serveur a des objectifs avec les memes noms
- **Solution Warp** : Nettoyage complet de l'etat client (scoreboards, boss bars, teams, mode hardcore) au switch

### 2.10 ServerConnectedEvent mal concu (Velocity #569, ouvert depuis 2021)
- **Probleme** : Fire entre deconnexion de l'ancien serveur et connexion au nouveau. `getCurrentServer()` retourne null. Reconnu comme "fatally flawed" par les maintainers
- **Solution Warp** : Evenements precis : PreSwitch (avant deconnexion ancien), Switching (pendant), PostSwitch (connexion etablie)

### 2.11 Desynchronisation mode Hardcore au switch (BungeeCord #2361)
- **Probleme** : Le flag hardcore est perdu lors du paquet Respawn au switch serveur
- **Solution Warp** : Preserver tous les flags du JoinGame original dans le Respawn

### 2.12 ClassLoader ne supporte pas les dependances inter-plugins (BungeeCord #3139)
- **Probleme** : Si plugin A bundle Kotlin et plugin B en depend, les classes de A ne sont pas visibles dans B
- **Solution Warp** : Classloader avec graphe de dependances declares entre plugins

### 2.13 Fuites memoire Netty (BungeeCord #2583, ouvert depuis 2019)
- **Probleme** : RAM monte continuellement (500MB -> 1.3GB -> crash). Buffers Netty jamais release
- **Solution Warp** : Direct buffers exclusivement, ResourceLeakDetector en dev, monitoring du pool Netty

### 2.14 CPU spike avec HAProxy (Velocity #1699)
- **Probleme** : Velocity boucle et consomme 100% CPU aleatoirement avec HAProxy
- **Solution Warp** : Tests de charge avec HAProxy dans la CI. PROXY protocol bien implemente

### 2.15 Registries moddees pas synchronisees (Velocity #922)
- **Probleme** : Registries moddees (Fabric) pas correctement synchronisees au switch serveur, causant des crashes client
- **Solution Warp** : Le blind forwarding evite ce probleme pour la majorite des cas

---

## 3. PROBLEMES IMPORTANTS (11)

### 3.1 Pas d'image Docker officielle (Velocity #368, BungeeCord #2210)
### 3.2 Pas de health probes standardisees (ni Velocity ni BungeeCord)
### 3.3 Pas de metrics Prometheus/OpenTelemetry natives
### 3.4 Pas de service discovery (backends statiques)
### 3.5 Pas de systeme de queue integre pour les gros reseaux
### 3.6 Systeme de commandes primitif (BungeeCord #832, #2812 -- pas de Brigadier)
### 3.7 Pas de ServicesManager / DI inter-plugins (BungeeCord #3199)
### 3.8 Serialisation JSON/NBT lente (BungeeCord #3624, #3852)
### 3.9 Pas de write batching (BungeeCord #3392)
### 3.10 Licence non-libre de BungeeCord (#1873) -- clause non-commerciale incompatible GPL
### 3.11 PROXY Protocol vers les backends refuse (Velocity #1504, closed NOT_PLANNED)

---

## 4. INNOVATIONS DES ALTERNATIVES A INTEGRER

| Innovation | Origine | Pertinence Warp |
|-----------|---------|-----------------|
| **Mode Lite** (reverse-proxy pur sans deserialisation) | Gate | HAUTE -- mode additionnel pour les setups simples |
| **5 modes de proxy** (passthrough, zerocopy, client_only, server_only, offline) | Infrarust | MOYENNE -- au moins 2 modes (full proxy + blind forwarding) |
| **Cache de ping** (repondre aux status requests sans TCP vers les backends) | Gate Lite | HAUTE -- protection anti-ping flood |
| **Serveurs virtuels / Limbo** (auth, anti-bot, queue sans backend dedie) | Elytrium LimboAPI | HAUTE -- killer feature pour le DX |
| **Auto-decouverte Docker** (labels Docker -> routage automatique) | Infrarust | MOYENNE -- pertinent pour les deploiements Docker |
| **Dashboard web** avec REST API + SSE | Infrarust | MOYENNE -- API REST oui, dashboard optionnel |
| **Transfer Packet natif** (1.20.5+) | TransferProxy | HAUTE -- deja dans le design du drain system |
| **Load balancing integre** (round-robin, least-connections, lowest-latency) | Gate Lite | HAUTE -- Velocity n'a rien de natif |
| **Wildcard hostname routing** (`*.domain.com` -> `$1.backend:25565`) | Gate | HAUTE -- trivial a implementer |
| **Cross-play Bedrock integre** (Geyser natif) | Gate | BASSE (Phase 6) |

---

## 5. CE QUE BUNGEECORD A BIEN FAIT (a preserver)

1. **Multi-listener natif** -- Velocity ne l'a toujours pas
2. **Forced hosts** -- Redirection par hostname, essentiel pour les gros reseaux
3. **Simplicite de configuration** -- Un fichier, facile a comprendre
4. **Stabilite API long terme** -- Les plugins de 2015 fonctionnent encore
5. **Communaute et documentation** -- Plus de plugins disponibles grace a l'anciennete

---

## 6. MATRICE DE PRIORITES POUR WARP

### Phase 1 (Protocol Foundation) -- DOIT resoudre :
- [x] Blind forwarding (1.1)
- [x] Machine a etats rigoureuse (1.4, 1.6)
- [x] HMAC-SHA256 forwarding (1.9)
- [x] Pas d'entity ID rewriting (2.2)

### Phase 2 (Core Proxy) -- DOIT resoudre :
- [x] Garanties de concurrence des evenements (1.3, 1.7)
- [x] Switch serveur fiable (1.4, 2.9, 2.10, 2.11)
- [x] Forwarding configurable par serveur (1.11)
- [x] Write batching (3.9)
- [x] Multi-listener (2.3)

### Phase 3 (Cloud-Native) -- DOIT resoudre :
- [x] Drain system multi-phase (1.2)
- [x] Health probes (3.2)
- [x] Prometheus metrics (3.3)
- [x] Rate-limiting natif (1.10)
- [x] Protection OOM/decompression (1.8)
- [x] Image Docker officielle (3.1)

### Phase 4 (Plugin API) -- DOIT resoudre :
- [x] DI complet + services inter-plugins (3.7)
- [x] Evenements avec garanties d'ordre (1.7)
- [x] Framework de commandes moderne (3.6)
- [x] ClassLoader avec dependances (2.12)
- [x] Tab list API complete (2.6)
- [x] Canal headless proxy-backend (1.12)
- [x] Pipeline Netty accessible (Velocity #594)

### Phase 5 (Production Polish) -- DOIT resoudre :
- [x] Load balancing integre (round-robin, least-connections)
- [x] Wildcard hostname routing
- [x] Cache de ping
- [x] Hot-reload complet (2.5)
- [x] Watchdog event loops (1.5)
- [x] Nettoyage scoreboard/bossbar au switch (2.9)

### Phase 6 (Advanced) -- PEUT resoudre :
- [ ] Serveurs virtuels / Limbo
- [ ] Mode Lite (reverse-proxy pur)
- [ ] Auto-decouverte Docker
- [ ] Cross-play Bedrock (Geyser)
- [ ] Multi-version (ViaVersion)
- [ ] Scaling horizontal natif

---

## 7. REFERENCES COMPLETES

### Velocity (PaperMC/Velocity)
| Issue | Titre | Reactions | Commentaires | Etat |
|-------|-------|-----------|-------------|------|
| #594 | Tighter pipeline control | 6 | 1 | Ouverte |
| #431 | Improved restart functionality | 22 | 0 | Ouverte |
| #1046 | Multiple Listeners | 17 | 17 | Ouverte |
| #566 | Backend Servers Rearchitecture | 10 | 0 | Ouverte |
| #248 | Forge 1.14+ support | 9 | 21 | Ouverte |
| #1251 | Unexpectedly disconnected from remote server | 0 | 53 | Ouverte |
| #1013 | Concurrency guarantees of connection events | 0 | 16 | Ouverte |
| #289 | DisconnectEvent ne fire pas au shutdown | 0 | 0 | Ouverte |
| #1691 | DisconnectEvent couvre trop de cas | 3 | 0 | Ouverte |
| #1723 | Re-entree en configuration phase | 0 | 0 | Ouverte |
| #804 | Chat manipulation cassee 1.19.1 | 0 | 0 | Ouverte |
| #1742 | Attaque de decompression OOM | 0 | 0 | Ouverte |
| #1699 | CPU spike avec HAProxy | 0 | 0 | Ouverte |
| #888 | Custom player info forwarding | 3 | 0 | In progress |
| #1488 | Communication proxy-Paper | 2 | 13 | Ouverte |
| #569 | ServerConnectedEvent flawed | 0 | 0 | Ouverte |
| #922 | Datapack registries desynced | 0 | 0 | Ouverte |
| #403 | Java 9 Jigsaw modularization | 7 | 0 | Ouverte |
| #246 | Backend transport independence | 3 | 0 | Ouverte |

### BungeeCord (SpigotMC/BungeeCord)
| Issue | Titre | Reactions | Etat |
|-------|-------|-----------|------|
| #3519 | Plugin message disconnect en ServerSwitchEvent | 11 | Fermee |
| #2361 | Desync mode Hardcore | 5 | Ouverte |
| #2812 | Support Brigadier | 3 | Ouverte |
| #1858 | AsyncEvent defaillant | 0 | Ouverte |
| #217 | Support cluster natif | 0 | Ouverte (2013!) |
| #1328 | Redesign IP forwarding | 0 | Ouverte (2015!) |
| #3358 | Proxy cesse d'accepter connexions | 0 | Ouverte |
| #3542 | Login apres ServerSwitchEvent 1.20.2 | 0 | Ouverte |
| #2815 | PostLogin avant Disconnect | 2 | Ouverte |
| #2583 | Fuite memoire Netty | 1 | Ouverte (2019!) |
| #3624 | Remplacer Gson | 0 | Ouverte |
| #3392 | Write batching | 0 | Ouverte |
| #2854 | Attaque DoS HTTP | 0 | Ouverte |
| #3503 | Scoreboards non nettoyes au switch | 0 | Ouverte |
| #832 | Systeme de commandes primitif | 0 | Ouverte (2014!) |
| #1873 | Licence non-libre | 2 | Ouverte (2016!) |

### Proxys alternatifs analyses
| Projet | Langage | Stars | Memoire | Innovation cle |
|--------|---------|-------|---------|----------------|
| Gate | Go | 989 | ~10 MB | Mode Lite, cloud-native, cross-play |
| Infrarust | Rust | 156 | Tres faible | 5 modes proxy, zerocopy, Docker auto-discovery |
| Infrared | Go | 847 | Tres faible | Placeholder d'inactivite, auto-start |
| Hopper-rs | Rust | 201 | Faible | Load balancing, Forge support |
| TransferProxy | Java | 64 | Faible | Transfer Packet natif |
| Velocity-CTD | Java | Fork | JVM | Redis natif, queue, multi-forwarding |
| Elytrium LimboAPI | Java | 294 | Plugin | Serveurs virtuels dans le proxy |
